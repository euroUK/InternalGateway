package bank.internalgateway.gateway.observability;

import bank.internalgateway.gateway.config.GatewayProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class RequestTraceService {

    private final int maxEntries;
    private final int topRoutesLimit;
    private final ConcurrentLinkedDeque<RequestTraceEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalRecorded = new AtomicLong();

    public RequestTraceService(GatewayProperties properties) {
        GatewayProperties.Observability observability = properties.observability();
        this.maxEntries = observability != null ? observability.traceBufferSize() : 500;
        this.topRoutesLimit = observability != null ? observability.topRoutesLimit() : 10;
    }

    public void record(RequestTraceEntry entry) {
        entries.addFirst(entry);
        totalRecorded.incrementAndGet();
        while (entries.size() > maxEntries) {
            entries.pollLast();
        }
    }

    public RequestTraceEntry start(String plane, String method, String inboundPath, String targetService, String targetUrl) {
        return new RequestTraceEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                plane,
                method,
                inboundPath,
                targetService,
                targetUrl,
                null,
                "IN_PROGRESS",
                0L,
                null
        );
    }

    public void complete(RequestTraceEntry entry, String status, long durationMs, String detail) {
        record(new RequestTraceEntry(
                entry.id(),
                entry.timestamp(),
                entry.plane(),
                entry.method(),
                entry.inboundPath(),
                entry.targetService(),
                entry.targetUrl(),
                entry.correlationId(),
                status,
                durationMs,
                detail
        ));
    }

    public void recordSimple(
            String plane,
            String method,
            String inboundPath,
            String targetService,
            String targetUrl,
            String correlationId,
            String status,
            long durationMs,
            String detail) {
        record(new RequestTraceEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                plane,
                method,
                inboundPath,
                targetService,
                targetUrl,
                correlationId,
                status,
                durationMs,
                detail
        ));
    }

    public List<RequestTraceEntry> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, maxEntries));
        return entries.stream().limit(safeLimit).toList();
    }

    public RequestStats stats() {
        List<RequestTraceEntry> snapshot = new ArrayList<>(entries);
        long success = snapshot.stream().filter(e -> e.status().startsWith("2") || "SUCCESS".equals(e.status())).count();
        long error = snapshot.stream().filter(e -> e.status().startsWith("4") || e.status().startsWith("5") || "ERROR".equals(e.status())).count();
        long dedup = snapshot.stream().filter(e -> "DEDUP".equals(e.status())).count();
        long rateLimited = snapshot.stream().filter(e -> "RATE_LIMIT".equals(e.status())).count();

        Map<String, Long> byPlane = snapshot.stream()
                .collect(Collectors.groupingBy(RequestTraceEntry::plane, Collectors.counting()));

        Map<String, Long> byRoute = snapshot.stream()
                .collect(Collectors.groupingBy(
                        e -> e.method() + " " + e.inboundPath(),
                        Collectors.counting()));

        List<RouteStat> topRoutes = byRoute.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topRoutesLimit)
                .map(e -> new RouteStat(e.getKey(), e.getValue()))
                .toList();

        DoubleSummaryStatistics latency = snapshot.stream()
                .filter(e -> e.durationMs() > 0)
                .mapToDouble(RequestTraceEntry::durationMs)
                .summaryStatistics();

        return new RequestStats(
                totalRecorded.get(),
                snapshot.size(),
                success,
                error,
                dedup,
                rateLimited,
                byPlane,
                topRoutes,
                latency.getCount() == 0 ? 0 : latency.getAverage(),
                latency.getCount() == 0 ? 0 : latency.getMax()
        );
    }

    public record RequestTraceEntry(
            String id,
            Instant timestamp,
            String plane,
            String method,
            String inboundPath,
            String targetService,
            String targetUrl,
            String correlationId,
            String status,
            long durationMs,
            String detail
    ) {
    }

    public record RouteStat(String route, long count) {
    }

    public record RequestStats(
            long totalRecorded,
            int bufferedEntries,
            long successCount,
            long errorCount,
            long dedupCount,
            long rateLimitedCount,
            Map<String, Long> byPlane,
            List<RouteStat> topRoutes,
            double avgDurationMs,
            double maxDurationMs
    ) {
    }
}
