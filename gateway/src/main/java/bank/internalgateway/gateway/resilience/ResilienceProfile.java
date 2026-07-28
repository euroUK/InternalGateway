package bank.internalgateway.gateway.resilience;

import java.time.Duration;
import java.util.List;

public record ResilienceProfile(
        String name,
        int maxAttempts,
        List<String> retryOn,
        List<String> doNotRetryOn,
        String backoff,
        Duration timeout,
        CircuitBreakerConfig circuitBreaker,
        DeadLetterConfig deadLetter,
        long backoffBaseMs,
        long backoffJitterMs
) {
    public static ResilienceProfile minimal(String name, long backoffBaseMs, long backoffJitterMs) {
        return new ResilienceProfile(
                name,
                1,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                backoffBaseMs,
                backoffJitterMs
        );
    }

    public boolean shouldRetry(int attempt, int statusCode, boolean connectFailure) {
        if (attempt >= maxAttempts) {
            return false;
        }
        String signal = connectFailure ? "connect-timeout" : "http-" + statusCode;
        if (doNotRetryOn != null && doNotRetryOn.stream().anyMatch(signal::equals)) {
            return false;
        }
        if (retryOn == null || retryOn.isEmpty()) {
            return statusCode >= 500 || connectFailure;
        }
        return retryOn.stream().anyMatch(signal::equals);
    }

    public long backoffMillis(int attempt) {
        long base = switch (backoff != null ? backoff : "") {
            case "exponential-jitter" -> (long) Math.pow(2, attempt - 1) * backoffBaseMs;
            default -> backoffBaseMs;
        };
        long jitter = (long) (Math.random() * backoffJitterMs);
        return base + jitter;
    }
}
