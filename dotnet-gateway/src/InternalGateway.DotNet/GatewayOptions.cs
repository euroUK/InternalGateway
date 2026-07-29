namespace InternalGateway.DotNet;

public sealed class GatewayOptions
{
    public const string SectionName = "Gateway";

    public string DslPath { get; set; } = "../dsl";
    public string EnvelopeSecret { get; set; } = "poc-envelope-secret-min-32-chars!!";
    public string CorsAllowedOrigins { get; set; } = "http://localhost:3000";
    public string DepositOfferServiceUrl { get; set; } = "http://localhost:8090";
    public string SelfBaseUrl { get; set; } = "http://127.0.0.1:8082";
    public string KafkaBootstrapServers { get; set; } = "localhost:9092";
    public string[] ListenerBindings { get; set; } = ["deposit-processor-offer-lifecycle"];
    public bool EnableKafka { get; set; } = true;
    public EnvelopeOptions Envelope { get; set; } = new();
    public DedupOptions Dedup { get; set; } = new();
    public ObservabilityOptions Observability { get; set; } = new();
    public ResilienceOptions Resilience { get; set; } = new();
}

public sealed class EnvelopeOptions
{
    public string Issuer { get; set; } = "internal-gateway";
    public int TtlSeconds { get; set; } = 30;
    public string BusinessControlEvidenceId { get; set; } = "poc-stub-passed";
}

public sealed class DedupOptions
{
    public int MaximumSize { get; set; } = 10_000;
    public TimeSpan DefaultTtl { get; set; } = TimeSpan.FromDays(7);
}

public sealed class ObservabilityOptions
{
    public int TraceBufferSize { get; set; } = 500;
    public int TopRoutesLimit { get; set; } = 10;
}

public sealed class ResilienceOptions
{
    public long BackoffBaseMs { get; set; } = 100;
    public long BackoffJitterMs { get; set; } = 50;
}
