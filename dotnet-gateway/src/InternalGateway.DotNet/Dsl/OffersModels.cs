namespace InternalGateway.DotNet.Dsl;

public sealed record CompiledOffersModule(
    string ModuleName,
    string ModuleVersion,
    EnvelopePolicy EnvelopePolicy,
    IReadOnlyList<CompiledIngressRoute> IngressRoutes,
    IReadOnlyList<CompiledCapabilityRoute> Capabilities);

public sealed record EnvelopePolicy(
    string Issuer,
    int TtlSeconds,
    IReadOnlyList<string> Claims,
    string BusinessControlEvidenceId);

public sealed record CompiledAdapterStep(
    string CapabilityId,
    string Method,
    string PathTemplate);

public sealed record CompiledIngressRoute(
    string RouteId,
    string Method,
    string InboundPath,
    string IdentityContext,
    string TargetService,
    string TargetMethod,
    string TargetPath,
    bool BusinessControlStub,
    string BusinessControlEvidenceId,
    CompiledAdapterStep? Adapter,
    IReadOnlyDictionary<string, string> ResponseMapping)
{
    public bool IsEnriched => Adapter is not null;
}

public sealed record CompiledCapabilityRoute(
    string CapabilityId,
    string Method,
    string PathTemplate,
    string ExecutionMode,
    IReadOnlyDictionary<string, object?> ResponseTemplate,
    string? TargetService,
    string? TargetUrl);
