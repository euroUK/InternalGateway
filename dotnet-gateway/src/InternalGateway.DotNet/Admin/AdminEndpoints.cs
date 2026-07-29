using InternalGateway.DotNet.Dsl;
using InternalGateway.DotNet.Identity;
using InternalGateway.DotNet.Messaging;
using InternalGateway.DotNet.Observability;
using Microsoft.Extensions.Options;

namespace InternalGateway.DotNet.Admin;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var admin = endpoints.MapGroup("/internal/admin");

        admin.MapGet("/stats", (RequestTraceService traces) => Results.Json(traces.Stats()));
        admin.MapGet("/requests", (RequestTraceService traces, int limit = 100) =>
            Results.Json(new { items = traces.Recent(limit), stats = traces.Stats() }));

        admin.MapGet("/dsl/config", (OffersRouteRegistryAccessor offers) =>
            Results.Json(DslSnapshot(offers.Registry.CurrentSnapshot)));

        admin.MapPost("/dsl/reload", (OffersRouteRegistryAccessor offers) =>
        {
            var result = offers.Registry.Reload();
            if (result.Success)
            {
                return Results.Json(new
                {
                    status = "reloaded",
                    success = true,
                    config = DslSnapshot(result.Current!)
                });
            }

            return Results.BadRequest(new
            {
                status = "rejected",
                success = false,
                error = result.Error ?? "reload failed",
                config = result.Current is null ? null : DslSnapshot(result.Current)
            });
        });

        admin.MapGet("/config", (
            OffersRouteRegistryAccessor offers,
            ConsumeBindingRegistry bindings,
            IOptions<GatewayOptions> options) =>
        {
            var module = offers.Registry.CurrentModule;
            return Results.Json(new
            {
                runtime = new
                {
                    dslPath = options.Value.DslPath,
                    services = new
                    {
                        depositOfferService = options.Value.DepositOfferServiceUrl
                    },
                    kafkaListenerBindings = options.Value.ListenerBindings
                },
                ingressRoutes = module.IngressRoutes,
                capabilities = module.Capabilities,
                messagingBindings = bindings.AllBindings().Select(b => new
                {
                    b.BindingId,
                    b.PhysicalTopic,
                    b.ConsumerGroup,
                    b.MappingFile,
                    targetCount = b.FanOutTargets.Count
                })
            });
        });

        return endpoints;
    }

    private static object DslSnapshot(OffersRouteRegistry.Snapshot snapshot)
    {
        var module = snapshot.Module;
        return new
        {
            moduleName = module.ModuleName,
            moduleVersion = module.ModuleVersion,
            version = snapshot.Version,
            status = snapshot.Status,
            loadedAt = snapshot.LoadedAt,
            routeCount = module.IngressRoutes.Count,
            capabilityCount = module.Capabilities.Count,
            ingressRoutes = module.IngressRoutes,
            capabilities = module.Capabilities,
            envelopePolicy = module.EnvelopePolicy
        };
    }
}
