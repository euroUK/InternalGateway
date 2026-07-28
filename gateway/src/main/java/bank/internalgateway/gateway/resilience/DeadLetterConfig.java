package bank.internalgateway.gateway.resilience;

public record DeadLetterConfig(
        String topicAlias,
        boolean retainForReplay,
        boolean enforced
) {
    public static DeadLetterConfig declared(String topicAlias, boolean retainForReplay) {
        return new DeadLetterConfig(topicAlias, retainForReplay, false);
    }
}
