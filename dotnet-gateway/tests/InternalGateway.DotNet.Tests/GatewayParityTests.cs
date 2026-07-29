using System.Net;
using System.Text;
using System.Text.Json;
using InternalGateway.DotNet.Dsl;
using InternalGateway.DotNet.Messaging;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace InternalGateway.DotNet.Tests;

public class OffersModuleCompilerTests
{
    [Fact]
    public void CompilesRepoOffersDslAndRendersCapability()
    {
        var dslDir = FindDslDirectory();
        var module = OffersModuleCompiler.CompileFromDirectory(dslDir);
        Assert.Equal("deposit-offers", module.ModuleName);
        Assert.Equal(2, module.IngressRoutes.Count);
        Assert.Equal(2, module.Capabilities.Count);

        var route = module.IngressRoutes[0];
        Assert.Equal("/deposit-offers/search", route.InboundPath);
        Assert.Equal("/internal/v1/offers/search", route.TargetPath);
        Assert.False(route.IsEnriched);

        var enriched = module.IngressRoutes.First(r => r.InboundPath == "/deposit-offers/enriched");
        Assert.True(enriched.IsEnriched);
        Assert.Equal("/internal/v1/offers/fixed", enriched.TargetPath);
        Assert.Equal("organization-display-info", enriched.Adapter!.CapabilityId);
        Assert.Equal("adapter.displayName", enriched.ResponseMapping["organizationDisplayName"]);

        var rendered = StaticCapabilityRenderer.Render(
            module.Capabilities[0],
            "/internal/capabilities/accounts/acc-1/deposit-context");
        Assert.Equal("acc-1", rendered["accountId"]?.ToString());
        Assert.Equal("RUB", rendered["currency"]?.ToString());
    }

    [Fact]
    public void ReloadKeepsLastKnownGood()
    {
        var temp = Directory.CreateTempSubdirectory("offers-dsl-");
        try
        {
            var source = Path.Combine(FindDslDirectory(), OffersModuleCompiler.OffersDslFile);
            var target = Path.Combine(temp.FullName, OffersModuleCompiler.OffersDslFile);
            File.Copy(source, target);
            var registry = new OffersRouteRegistry(temp.FullName);
            registry.LoadInitial();
            var version = registry.CurrentSnapshot.Version;

            File.WriteAllText(target, "metadata:\n  name: broken\nroutes: []\n");
            var result = registry.Reload();
            Assert.False(result.Success);
            Assert.Equal(version, registry.CurrentSnapshot.Version);
            Assert.Equal(2, registry.CurrentModule.IngressRoutes.Count);
        }
        finally
        {
            temp.Delete(true);
        }
    }

    internal static string FindDslDirectory()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, "dsl");
            if (File.Exists(Path.Combine(candidate, OffersModuleCompiler.OffersDslFile)))
            {
                return candidate;
            }

            dir = dir.Parent;
        }

        throw new DirectoryNotFoundException("dsl/ not found");
    }
}

public class GatewayHttpParityTests : IClassFixture<GatewayWebApplicationFactory>
{
    private readonly GatewayWebApplicationFactory _factory;
    private readonly HttpClient _client;

    public GatewayHttpParityTests(GatewayWebApplicationFactory factory)
    {
        _factory = factory;
        _client = factory.CreateClient();
    }

    [Fact]
    public async Task ProxiesSearchWithJwtAndPathRewrite()
    {
        _factory.Backend.Enqueue(req =>
        {
            Assert.Equal(HttpMethod.Post, req.Method);
            Assert.Equal("/internal/v1/offers/search", req.RequestUri!.AbsolutePath);
            Assert.True(req.Headers.Contains("X-Identity-Envelope"));
            Assert.True(req.Headers.Contains("X-Correlation-Id"));
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"accountId":"acc-demo-001","offers":[]}""",
                    Encoding.UTF8,
                    "application/json")
            };
        });

        using var request = new HttpRequestMessage(HttpMethod.Post, "/deposit-offers/search");
        request.Headers.TryAddWithoutValidation("X-Demo-Subject-Id", "demo-user-001");
        request.Headers.TryAddWithoutValidation("X-Demo-Organization-Id", "org-demo-001");
        request.Content = new StringContent(
            """{"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}""",
            Encoding.UTF8,
            "application/json");

        var response = await _client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.Contains("X-Correlation-Id"));
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("acc-demo-001", body);
    }

    [Fact]
    public async Task EnrichedLoopsOrgCapabilityAndMapsFixedOffer()
    {
        _factory.Backend.Enqueue(req =>
        {
            Assert.Equal(HttpMethod.Get, req.Method);
            Assert.Equal(
                "/internal/capabilities/organizations/org-demo-001/display-info",
                req.RequestUri!.AbsolutePath);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"organizationId":"org-demo-001","displayName":"Demo Organization LLC","legalForm":"LLC"}""",
                    Encoding.UTF8,
                    "application/json")
            };
        });
        _factory.Backend.Enqueue(req =>
        {
            Assert.Equal(HttpMethod.Post, req.Method);
            Assert.Equal("/internal/v1/offers/fixed", req.RequestUri!.AbsolutePath);
            Assert.True(req.Headers.Contains("X-Identity-Envelope"));
            var body = req.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
            Assert.Contains("organizationDisplayName", body);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"productCode":"FIXED-DEP-12","rate":0.125,"termMonths":12,"currency":"RUB","minAmount":100000,"status":"AVAILABLE"}""",
                    Encoding.UTF8,
                    "application/json")
            };
        });

        using var request = new HttpRequestMessage(HttpMethod.Post, "/deposit-offers/enriched");
        request.Headers.TryAddWithoutValidation("X-Demo-Subject-Id", "demo-user-001");
        request.Content = new StringContent(
            """{"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}""",
            Encoding.UTF8,
            "application/json");

        var response = await _client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.Contains("X-Correlation-Id"));
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("Demo Organization LLC", json.RootElement.GetProperty("organizationDisplayName").GetString());
        Assert.Equal("FIXED-DEP-12", json.RootElement.GetProperty("offer").GetProperty("productCode").GetString());
        Assert.Equal(0.125d, json.RootElement.GetProperty("offer").GetProperty("rate").GetDouble(), 3);
        Assert.False(string.IsNullOrWhiteSpace(json.RootElement.GetProperty("correlationId").GetString()));
    }

    [Fact]
    public async Task CapabilityEndpointsRenderFromDsl()
    {
        var account = await _client.GetAsync("/internal/capabilities/accounts/acc-demo-001/deposit-context");
        Assert.Equal(HttpStatusCode.OK, account.StatusCode);
        using var accountJson = JsonDocument.Parse(await account.Content.ReadAsStringAsync());
        Assert.Equal("acc-demo-001", accountJson.RootElement.GetProperty("accountId").GetString());
        Assert.Equal("RUB", accountJson.RootElement.GetProperty("currency").GetString());

        var org = await _client.GetAsync("/internal/capabilities/organizations/org-demo-001/display-info");
        Assert.Equal(HttpStatusCode.OK, org.StatusCode);
        using var orgJson = JsonDocument.Parse(await org.Content.ReadAsStringAsync());
        Assert.Equal("Demo Organization LLC", orgJson.RootElement.GetProperty("displayName").GetString());
    }

    [Fact]
    public async Task DslReloadAppliesTargetPathAndRejectsInvalid()
    {
        var dslFile = Path.Combine(_factory.DslDirectory, OffersModuleCompiler.OffersDslFile);
        var original = await File.ReadAllTextAsync(dslFile);
        try
        {
            await File.WriteAllTextAsync(
                dslFile,
                original.Replace("/internal/v1/offers/search", "/internal/v1/offers/search-v2", StringComparison.Ordinal));

            var reload = await _client.PostAsync("/internal/admin/dsl/reload", null);
            Assert.Equal(HttpStatusCode.OK, reload.StatusCode);

            _factory.Backend.Enqueue(req =>
            {
                Assert.Equal("/internal/v1/offers/search-v2", req.RequestUri!.AbsolutePath);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"offers":[]}""", Encoding.UTF8, "application/json")
                };
            });

            using var search = new HttpRequestMessage(HttpMethod.Post, "/deposit-offers/search")
            {
                Content = new StringContent(
                    """{"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":1,"termMonths":1}""",
                    Encoding.UTF8,
                    "application/json")
            };
            Assert.Equal(HttpStatusCode.OK, (await _client.SendAsync(search)).StatusCode);

            await File.WriteAllTextAsync(dslFile, "metadata:\n  name: broken\nroutes: []\n");
            var rejected = await _client.PostAsync("/internal/admin/dsl/reload", null);
            Assert.Equal(HttpStatusCode.BadRequest, rejected.StatusCode);
        }
        finally
        {
            await File.WriteAllTextAsync(dslFile, original);
            await _client.PostAsync("/internal/admin/dsl/reload", null);
        }
    }
}

public sealed class GatewayWebApplicationFactory : WebApplicationFactory<Program>, IAsyncLifetime
{
    private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _handlers = new();
    public string DslDirectory { get; private set; } = string.Empty;
    public BackendQueue Backend { get; }

    public GatewayWebApplicationFactory()
    {
        Backend = new BackendQueue(_handlers);
    }

    protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder builder)
    {
        DslDirectory = Directory.CreateTempSubdirectory("dotnet-offers-dsl-").FullName;
        File.Copy(
            Path.Combine(OffersModuleCompilerTests.FindDslDirectory(), OffersModuleCompiler.OffersDslFile),
            Path.Combine(DslDirectory, OffersModuleCompiler.OffersDslFile));
        foreach (var file in Directory.EnumerateFiles(OffersModuleCompilerTests.FindDslDirectory(), "*.yaml"))
        {
            var name = Path.GetFileName(file);
            var dest = Path.Combine(DslDirectory, name);
            if (!File.Exists(dest))
            {
                File.Copy(file, dest);
            }
        }

        builder.UseSetting("Gateway:DslPath", DslDirectory);
        builder.UseSetting("Gateway:EnableKafka", "false");
        builder.UseSetting("Gateway:DepositOfferServiceUrl", "http://backend-test");
        builder.UseSetting("Gateway:SelfBaseUrl", "http://backend-test");
        builder.UseSetting("Gateway:EnvelopeSecret", "poc-envelope-secret-min-32-chars!!");
        Environment.SetEnvironmentVariable("GATEWAY_DISABLE_KAFKA", "true");
        Environment.SetEnvironmentVariable("GATEWAY_DSL_PATH", DslDirectory);
        Environment.SetEnvironmentVariable("DEPOSIT_OFFER_SERVICE_URL", "http://backend-test");
        Environment.SetEnvironmentVariable("GATEWAY_SELF_BASE_URL", "http://backend-test");

        builder.ConfigureTestServices(services =>
        {
            services.RemoveAll<IHttpClientFactory>();
            services.AddSingleton<IHttpClientFactory>(new QueueHttpClientFactory(_handlers));
        });
    }

    public Task InitializeAsync() => Task.CompletedTask;

    async Task IAsyncLifetime.DisposeAsync()
    {
        await base.DisposeAsync();
        if (Directory.Exists(DslDirectory))
        {
            Directory.Delete(DslDirectory, true);
        }
    }
}

public sealed class BackendQueue(Queue<Func<HttpRequestMessage, HttpResponseMessage>> handlers)
{
    public void Enqueue(Func<HttpRequestMessage, HttpResponseMessage> handler) => handlers.Enqueue(handler);
}

file sealed class QueueHttpClientFactory(Queue<Func<HttpRequestMessage, HttpResponseMessage>> handlers) : IHttpClientFactory
{
    public HttpClient CreateClient(string name) =>
        new(new QueueHandler(handlers), disposeHandler: true)
        {
            BaseAddress = new Uri("http://backend-test")
        };

    private sealed class QueueHandler(Queue<Func<HttpRequestMessage, HttpResponseMessage>> handlers) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (handlers.Count == 0)
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.BadGateway)
                {
                    Content = new StringContent("no queued backend response")
                });
            }

            return Task.FromResult(handlers.Dequeue()(request));
        }
    }
}

public class EventMapperTests
{
    [Fact]
    public void MapsProcessorHeadersAndPercentRate()
    {
        var dsl = OffersModuleCompilerTests.FindDslDirectory();
        var registry = new EventMappingRegistry();
        registry.Load(dsl);
        var mapper = new ConfigurableEventMapper(registry);
        var headers = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["messageId"] = "evt-1",
            ["messageType"] = "OFFER_CREATED",
            ["offerExternalId"] = "proc-1",
            ["publishedAt"] = "2026-01-01T00:00:00Z"
        };
        var payload = """{"offerExternalId":"proc-1","revision":3,"interestRatePercent":12.5,"currencyCode":"RUB"}""";
        var canonical = mapper.Map("processor-event-mapping.yaml", headers, payload);
        Assert.Equal("evt-1", canonical.EventId);
        Assert.Equal("DepositOfferCreated", canonical.EventType);
        Assert.Equal(0.125d, Assert.IsType<double>(canonical.Payload["rate"]), 3);
    }
}

public class DedupAndRateLimitTests
{
    [Fact]
    public void DedupDetectsDuplicates()
    {
        var cache = new EventDedupCache(Microsoft.Extensions.Options.Options.Create(new GatewayOptions()));
        Assert.False(cache.IsDuplicate("b1", TimeSpan.FromMinutes(1), "e1"));
        Assert.True(cache.IsDuplicate("b1", TimeSpan.FromMinutes(1), "e1"));
        Assert.Equal(1, cache.Hits);
    }
}
