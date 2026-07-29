namespace InternalGateway.DotNet.Messaging;

public static class DeliveryHeaders
{
    public const string DeliveryEnvelope = "X-Delivery-Envelope";
    public const string EventId = "X-Event-Id";
    public const string EventType = "X-Event-Type";
    public const string IdentityEnvelope = "X-Identity-Envelope";
    public const string CorrelationId = "X-Correlation-Id";
    public const string DemoSubjectId = "X-Demo-Subject-Id";
    public const string DemoOrganizationId = "X-Demo-Organization-Id";
}
