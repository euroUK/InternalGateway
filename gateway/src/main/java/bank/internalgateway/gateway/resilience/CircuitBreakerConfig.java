package bank.internalgateway.gateway.resilience;

import java.time.Duration;

public record CircuitBreakerConfig(
        int failureRateThreshold,
        int minimumCalls,
        Duration openDuration,
        boolean enforced
) {
    public static CircuitBreakerConfig declared(int failureRateThreshold, int minimumCalls, Duration openDuration) {
        return new CircuitBreakerConfig(failureRateThreshold, minimumCalls, openDuration, false);
    }
}
