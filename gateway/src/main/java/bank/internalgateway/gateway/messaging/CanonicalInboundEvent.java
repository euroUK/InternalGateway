package bank.internalgateway.gateway.messaging;

import java.util.Map;

public record CanonicalInboundEvent(
        String eventId,
        String eventType,
        String occurredAt,
        Map<String, Object> payload,
        String sourceMessageType,
        String mappingSummary
) {
}
