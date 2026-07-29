using InternalGateway.DotNet;
using InternalGateway.DotNet.Admin;
using InternalGateway.DotNet.Dsl;
using InternalGateway.DotNet.Identity;
using InternalGateway.DotNet.Messaging;
using InternalGateway.DotNet.Middleware;
using InternalGateway.DotNet.Observability;

var builder = WebApplication.CreateBuilder(args);

builder.Configuration.AddEnvironmentVariables();
builder.Services.Configure<GatewayOptions>(options =>
{
    builder.Configuration.GetSection(GatewayOptions.SectionName).Bind(options);
    var envDsl = Environment.GetEnvironmentVariable("GATEWAY_DSL_PATH");
    if (!string.IsNullOrWhiteSpace(envDsl))
    {
        options.DslPath = envDsl;
    }

    var envSecret = Environment.GetEnvironmentVariable("GATEWAY_ENVELOPE_SECRET");
    if (!string.IsNullOrWhiteSpace(envSecret))
    {
        options.EnvelopeSecret = envSecret;
    }

    var envService = Environment.GetEnvironmentVariable("DEPOSIT_OFFER_SERVICE_URL");
    if (!string.IsNullOrWhiteSpace(envService))
    {
        options.DepositOfferServiceUrl = envService;
    }

    var envSelf = Environment.GetEnvironmentVariable("GATEWAY_SELF_BASE_URL");
    if (!string.IsNullOrWhiteSpace(envSelf))
    {
        options.SelfBaseUrl = envSelf;
    }

    var envKafka = Environment.GetEnvironmentVariable("KAFKA_BOOTSTRAP_SERVERS");
    if (!string.IsNullOrWhiteSpace(envKafka))
    {
        options.KafkaBootstrapServers = envKafka;
    }

    var disableKafka = Environment.GetEnvironmentVariable("GATEWAY_DISABLE_KAFKA");
    if (string.Equals(disableKafka, "true", StringComparison.OrdinalIgnoreCase)
        || string.Equals(disableKafka, "1", StringComparison.OrdinalIgnoreCase))
    {
        options.EnableKafka = false;
    }

    var envPort = Environment.GetEnvironmentVariable("SERVER_PORT")
        ?? Environment.GetEnvironmentVariable("ASPNETCORE_HTTP_PORTS");
    if (!string.IsNullOrWhiteSpace(envPort) && int.TryParse(envPort.Split(';')[0], out var port))
    {
        builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
    }
});

builder.Services.AddCors();
builder.Services.AddHealthChecks();
builder.Services.AddSingleton<OffersRouteRegistryAccessor>();
builder.Services.AddSingleton<RequestTraceService>();
builder.Services.AddSingleton<IdentityEnvelopeService>();
builder.Services.AddSingleton<ConsumeBindingRegistry>();
builder.Services.AddSingleton<EventMappingRegistry>();
builder.Services.AddSingleton<ConfigurableEventMapper>();
builder.Services.AddSingleton<EventDedupCache>();
builder.Services.AddSingleton<GatewayRateLimiter>();
builder.Services.AddSingleton<ResilienceDeliveryExecutor>();
builder.Services.AddSingleton<EventFanOutService>();
builder.Services.AddSingleton<InboundEventPipeline>();
builder.Services.AddHostedService<KafkaConsumeHostedService>();

builder.Services.AddHttpClient("upstream")
    .ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
    {
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        MaxConnectionsPerServer = 256,
        EnableMultipleHttp2Connections = true
    })
    .SetHandlerLifetime(TimeSpan.FromMinutes(10));

var app = builder.Build();

var gatewayOptions = app.Services.GetRequiredService<Microsoft.Extensions.Options.IOptions<GatewayOptions>>().Value;
var dslPath = Path.GetFullPath(gatewayOptions.DslPath);
var offersRegistry = new OffersRouteRegistry(dslPath);
offersRegistry.LoadInitial();
app.Services.GetRequiredService<OffersRouteRegistryAccessor>().Registry = offersRegistry;

var bindings = app.Services.GetRequiredService<ConsumeBindingRegistry>();
bindings.Load(dslPath);
app.Services.GetRequiredService<EventMappingRegistry>().Load(dslPath);

var origins = gatewayOptions.CorsAllowedOrigins.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
app.UseCors(policy => policy.WithOrigins(origins).AllowAnyHeader().AllowAnyMethod());

app.UseMiddleware<IngressProxyMiddleware>();
app.UseMiddleware<StaticCapabilityMiddleware>();
app.MapHealthChecks("/health");
app.MapHealthChecks("/actuator/health");
app.MapAdminEndpoints();

app.Logger.LogInformation("DotNet Internal Gateway listening; DSL={DslPath}", dslPath);
app.Run();

public partial class Program;
