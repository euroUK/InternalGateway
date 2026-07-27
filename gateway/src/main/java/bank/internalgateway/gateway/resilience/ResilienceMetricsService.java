package bank.internalgateway.gateway.resilience;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ResilienceMetricsService {

    private final AtomicLong retryAttempts = new AtomicLong();
    private final AtomicLong retrySuccesses = new AtomicLong();
    private final AtomicLong retryFailures = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();

    public void recordRetryAttempt() {
        retryAttempts.incrementAndGet();
    }

    public void recordRetrySuccess() {
        retrySuccesses.incrementAndGet();
    }

    public void recordRetryFailure() {
        retryFailures.incrementAndGet();
    }

    public void recordRateLimited(String profileName) {
        rateLimited.incrementAndGet();
    }

    public ResilienceStatsView snapshot(EventDedupCache dedupCache, GatewayRateLimiter rateLimiter) {
        return new ResilienceStatsView(
                dedupCache.dedupHits(),
                dedupCache.cacheSizes(),
                retryAttempts.get(),
                retrySuccesses.get(),
                retryFailures.get(),
                rateLimited.get(),
                rateLimiter.snapshot()
        );
    }

    public record ResilienceStatsView(
            long dedupHits,
            Map<String, Long> dedupCacheSizes,
            long retryAttempts,
            long retrySuccesses,
            long retryFailures,
            long rateLimitedCount,
            Map<String, GatewayRateLimiter.BucketStatsView> rateLimitBuckets
    ) {
    }
}
