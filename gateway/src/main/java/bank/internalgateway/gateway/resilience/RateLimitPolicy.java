package bank.internalgateway.gateway.resilience;

public record RateLimitPolicy(
        String name,
        double sustainedPerSecond,
        int burst
) {
    public static RateLimitPolicy disabled(String name) {
        return new RateLimitPolicy(name, Double.MAX_VALUE, Integer.MAX_VALUE);
    }
}
