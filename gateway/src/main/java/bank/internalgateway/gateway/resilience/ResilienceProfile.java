package bank.internalgateway.gateway.resilience;

import java.util.List;

public record ResilienceProfile(
        String name,
        int maxAttempts,
        List<String> retryOn,
        List<String> doNotRetryOn,
        String backoff
) {
    public static ResilienceProfile defaults(String name) {
        return new ResilienceProfile(
                name,
                1,
                List.of("http-502", "http-503", "http-504", "connect-timeout"),
                List.of("http-409", "http-422"),
                "exponential-jitter"
        );
    }

    public boolean shouldRetry(int attempt, int statusCode, boolean connectFailure) {
        if (attempt >= maxAttempts) {
            return false;
        }
        String signal = connectFailure ? "connect-timeout" : "http-" + statusCode;
        if (doNotRetryOn.stream().anyMatch(signal::equals)) {
            return false;
        }
        if (retryOn.isEmpty()) {
            return statusCode >= 500 || connectFailure;
        }
        return retryOn.stream().anyMatch(signal::equals);
    }

    public long backoffMillis(int attempt) {
        long base = switch (backoff != null ? backoff : "") {
            case "exponential-jitter" -> (long) Math.pow(2, attempt - 1) * 100L;
            default -> 100L;
        };
        long jitter = (long) (Math.random() * 50);
        return base + jitter;
    }
}
