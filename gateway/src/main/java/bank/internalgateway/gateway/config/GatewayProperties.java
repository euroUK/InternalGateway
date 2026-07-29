package bank.internalgateway.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String dslPath,
        String envelopeSecret,
        String corsAllowedOrigins,
        Map<String, String> services,
        String selfBaseUrl,
        Envelope envelope,
        Dedup dedup,
        Resilience resilience,
        Observability observability,
        Kafka kafka,
        TestHarness testHarness
) {
    public record Envelope(
            String issuer,
            Integer ttlSeconds,
            String businessControlEvidenceId
    ) {
    }

    public record Dedup(
            int maximumSize,
            Duration defaultTtl
    ) {
    }

    public record Resilience(
            Backoff backoff
    ) {
        public record Backoff(
                long baseMs,
                long jitterMs
        ) {
        }
    }

    public record Observability(
            int traceBufferSize,
            int topRoutesLimit
    ) {
    }

    public record Kafka(
            List<String> listenerBindings
    ) {
    }

    public record TestHarness(
            String defaultBindingId,
            String faultPath,
            int defaultFaultStatus,
            String demoPublishPath
    ) {
    }
}
