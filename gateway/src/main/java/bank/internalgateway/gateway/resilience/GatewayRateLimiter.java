package bank.internalgateway.gateway.resilience;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GatewayRateLimiter {

    private final ResilienceProfileRegistry profileRegistry;
    private final ResilienceMetricsService metricsService;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public GatewayRateLimiter(
            ResilienceProfileRegistry profileRegistry,
            ResilienceMetricsService metricsService) {
        this.profileRegistry = profileRegistry;
        this.metricsService = metricsService;
    }

    public boolean tryAcquire(String profileName, String scopeKey) {
        RateLimitPolicy policy = profileRegistry.getRateLimit(profileName);
        if (policy.sustainedPerSecond() >= Double.MAX_VALUE / 2) {
            return true;
        }
        String bucketKey = profileName + ":" + scopeKey;
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey, key -> new TokenBucket(policy));
        bucket.refreshPolicy(policy);
        if (bucket.tryAcquire()) {
            return true;
        }
        metricsService.recordRateLimited(profileName);
        return false;
    }

    public Map<String, BucketStatsView> snapshot() {
        Map<String, BucketStatsView> stats = new ConcurrentHashMap<>();
        buckets.forEach((key, bucket) -> stats.put(key, bucket.stats()));
        return stats;
    }

    static final class TokenBucket {
        private volatile RateLimitPolicy policy;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(RateLimitPolicy policy) {
            this.policy = policy;
            this.tokens = policy.burst();
            this.lastRefillNanos = System.nanoTime();
        }

        void refreshPolicy(RateLimitPolicy policy) {
            this.policy = policy;
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized BucketStatsView stats() {
            return new BucketStatsView(policy.name(), tokens, policy.burst(), policy.sustainedPerSecond());
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds <= 0) {
                return;
            }
            tokens = Math.min(policy.burst(), tokens + elapsedSeconds * policy.sustainedPerSecond());
            lastRefillNanos = now;
        }
    }

    public record BucketStatsView(
            String profileName,
            double availableTokens,
            int burst,
            double sustainedPerSecond
    ) {
    }
}
