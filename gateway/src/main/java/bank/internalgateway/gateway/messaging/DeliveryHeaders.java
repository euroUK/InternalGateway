package bank.internalgateway.gateway.messaging;

/**
 * HTTP header names used when delivering events to downstream services.
 */
public final class DeliveryHeaders {

    public static final String DELIVERY_ENVELOPE = "X-Delivery-Envelope";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String EVENT_TYPE = "X-Event-Type";
    public static final String IDENTITY_ENVELOPE = "X-Identity-Envelope";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    private DeliveryHeaders() {
    }
}
