using System.Buffers;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using InternalGateway.DotNet.Dsl;
using InternalGateway.DotNet.Identity;
using InternalGateway.DotNet.Messaging;
using InternalGateway.DotNet.Observability;
using Microsoft.Extensions.Options;

namespace InternalGateway.DotNet.Middleware;

public sealed class IngressProxyMiddleware
{
    private static readonly PathString SearchPath = new("/deposit-offers/search");
    private static readonly PathString EnrichedPath = new("/deposit-offers/enriched");

    private readonly RequestDelegate _next;

    public IngressProxyMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(
        HttpContext context,
        OffersRouteRegistryAccessor offersAccessor,
        IdentityEnvelopeService identity,
        IHttpClientFactory httpClientFactory,
        IOptions<GatewayOptions> options,
        RequestTraceService traces)
    {
        if (!HttpMethods.IsPost(context.Request.Method))
        {
            await _next(context);
            return;
        }

        if (context.Request.Path.Equals(SearchPath))
        {
            await HandleSearchAsync(context, offersAccessor, identity, httpClientFactory, options, traces);
            return;
        }

        if (context.Request.Path.Equals(EnrichedPath))
        {
            await HandleEnrichedAsync(context, offersAccessor, identity, httpClientFactory, options, traces);
            return;
        }

        await _next(context);
    }

    private static async Task HandleSearchAsync(
        HttpContext context,
        OffersRouteRegistryAccessor offersAccessor,
        IdentityEnvelopeService identity,
        IHttpClientFactory httpClientFactory,
        IOptions<GatewayOptions> options,
        RequestTraceService traces)
    {
        var route = offersAccessor.Registry.CurrentModule.IngressRoutes
            .FirstOrDefault(r =>
                r.Method.Equals("POST", StringComparison.OrdinalIgnoreCase)
                && r.InboundPath.Equals(SearchPath.Value, StringComparison.Ordinal));
        if (route is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        var started = Environment.TickCount64;
        var correlationId = Guid.NewGuid().ToString("D");
        context.Response.Headers[DeliveryHeaders.CorrelationId] = correlationId;

        var bodyOwner = await BufferRequestBodyAsync(context.Request);
        try
        {
            var body = bodyOwner.Memory;
            var subjectId = FirstHeader(context, DeliveryHeaders.DemoSubjectId) ?? "demo-user-001";
            var organizationId = ResolveOrganizationId(context, body);

            var envelope = identity.CreateBankUserEnvelope(
                subjectId,
                organizationId,
                correlationId,
                route.BusinessControlEvidenceId);

            var baseUrl = options.Value.DepositOfferServiceUrl.TrimEnd('/');
            var targetUrl = baseUrl + route.TargetPath;

            var client = httpClientFactory.CreateClient("upstream");
            var payload = body.ToArray();
            using var request = new HttpRequestMessage(new HttpMethod(route.TargetMethod), targetUrl)
            {
                Content = new ByteArrayContent(payload)
            };
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            request.Headers.TryAddWithoutValidation(DeliveryHeaders.IdentityEnvelope, envelope);
            request.Headers.TryAddWithoutValidation(DeliveryHeaders.CorrelationId, correlationId);
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, context.RequestAborted);

            context.Response.StatusCode = (int)response.StatusCode;
            if (response.Content.Headers.ContentType is { } ct)
            {
                context.Response.ContentType = ct.ToString();
            }

            await response.Content.CopyToAsync(context.Response.Body, context.RequestAborted);

            traces.RecordSimple(
                "ingress",
                route.Method,
                route.InboundPath,
                route.TargetService,
                targetUrl,
                correlationId,
                ((int)response.StatusCode).ToString(),
                Environment.TickCount64 - started,
                "subjectId=" + subjectId + ", organizationId=" + organizationId);
        }
        catch (Exception ex)
        {
            traces.RecordSimple(
                "ingress",
                "POST",
                SearchPath.Value!,
                route.TargetService,
                options.Value.DepositOfferServiceUrl + route.TargetPath,
                correlationId,
                "ERROR",
                Environment.TickCount64 - started,
                ex.Message);
            throw;
        }
        finally
        {
            bodyOwner.Dispose();
        }
    }

    private static async Task HandleEnrichedAsync(
        HttpContext context,
        OffersRouteRegistryAccessor offersAccessor,
        IdentityEnvelopeService identity,
        IHttpClientFactory httpClientFactory,
        IOptions<GatewayOptions> options,
        RequestTraceService traces)
    {
        var route = offersAccessor.Registry.CurrentModule.IngressRoutes
            .FirstOrDefault(r =>
                r.Method.Equals("POST", StringComparison.OrdinalIgnoreCase)
                && r.InboundPath.Equals(EnrichedPath.Value, StringComparison.Ordinal));
        if (route is null || !route.IsEnriched || route.Adapter is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        var started = Environment.TickCount64;
        var correlationId = Guid.NewGuid().ToString("D");
        context.Response.Headers[DeliveryHeaders.CorrelationId] = correlationId;

        var bodyOwner = await BufferRequestBodyAsync(context.Request);
        var adapterPath = string.Empty;
        try
        {
            var body = bodyOwner.Memory;
            var subjectId = FirstHeader(context, DeliveryHeaders.DemoSubjectId) ?? "demo-user-001";
            var organizationId = ResolveOrganizationId(context, body);
            adapterPath = PathTemplate.Expand(
                route.Adapter.PathTemplate,
                new Dictionary<string, string>(StringComparer.Ordinal) { ["organizationId"] = organizationId });

            var selfBase = (options.Value.SelfBaseUrl ?? "http://127.0.0.1:8082").TrimEnd('/');
            var adapterUrl = selfBase + adapterPath;
            var targetBase = options.Value.DepositOfferServiceUrl.TrimEnd('/');
            var targetUrl = targetBase + route.TargetPath;

            var client = httpClientFactory.CreateClient("upstream");
            using var adapterRequest = new HttpRequestMessage(new HttpMethod(route.Adapter.Method), adapterUrl);
            adapterRequest.Headers.TryAddWithoutValidation(DeliveryHeaders.CorrelationId, correlationId);
            using var adapterResponse = await client.SendAsync(adapterRequest, context.RequestAborted);
            adapterResponse.EnsureSuccessStatusCode();
            var adapterJson = await adapterResponse.Content.ReadAsStringAsync(context.RequestAborted);
            using var adapterDoc = JsonDocument.Parse(string.IsNullOrWhiteSpace(adapterJson) ? "{}" : adapterJson);
            var displayName = adapterDoc.RootElement.TryGetProperty("displayName", out var dn)
                && dn.ValueKind == JsonValueKind.String
                    ? dn.GetString()
                    : null;

            var envelope = identity.CreateBankUserEnvelope(
                subjectId,
                organizationId,
                correlationId,
                route.BusinessControlEvidenceId);

            using var requestDoc = body.Length == 0
                ? JsonDocument.Parse("{}")
                : JsonDocument.Parse(body);
            using var targetPayload = new MemoryStream();
            using (var writer = new Utf8JsonWriter(targetPayload))
            {
                writer.WriteStartObject();
                foreach (var prop in requestDoc.RootElement.EnumerateObject())
                {
                    prop.WriteTo(writer);
                }

                if (!string.IsNullOrWhiteSpace(displayName))
                {
                    writer.WriteString("organizationDisplayName", displayName);
                }

                writer.WriteEndObject();
            }

            using var targetRequest = new HttpRequestMessage(new HttpMethod(route.TargetMethod), targetUrl)
            {
                Content = new ByteArrayContent(targetPayload.ToArray())
            };
            targetRequest.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            targetRequest.Headers.TryAddWithoutValidation(DeliveryHeaders.IdentityEnvelope, envelope);
            targetRequest.Headers.TryAddWithoutValidation(DeliveryHeaders.CorrelationId, correlationId);
            using var targetResponse = await client.SendAsync(targetRequest, context.RequestAborted);
            targetResponse.EnsureSuccessStatusCode();
            var targetJson = await targetResponse.Content.ReadAsStringAsync(context.RequestAborted);
            using var targetDoc = JsonDocument.Parse(string.IsNullOrWhiteSpace(targetJson) ? "{}" : targetJson);

            var mapped = EnrichedResponseMapper.Map(
                route.ResponseMapping,
                requestDoc.RootElement,
                adapterDoc.RootElement,
                targetDoc.RootElement,
                correlationId);

            context.Response.StatusCode = StatusCodes.Status200OK;
            context.Response.ContentType = "application/json; charset=utf-8";
            await JsonSerializer.SerializeAsync(context.Response.Body, mapped, cancellationToken: context.RequestAborted);

            traces.RecordSimple(
                "ingress",
                route.Method,
                route.InboundPath,
                route.TargetService,
                targetUrl,
                correlationId,
                "200",
                Environment.TickCount64 - started,
                "adapter=" + adapterPath + ", target=" + route.TargetPath
                    + ", subjectId=" + subjectId + ", organizationId=" + organizationId);
        }
        catch (Exception ex)
        {
            traces.RecordSimple(
                "ingress",
                "POST",
                EnrichedPath.Value!,
                route.TargetService,
                options.Value.DepositOfferServiceUrl + route.TargetPath,
                correlationId,
                "ERROR",
                Environment.TickCount64 - started,
                "adapter=" + adapterPath + ", " + ex.Message);
            throw;
        }
        finally
        {
            bodyOwner.Dispose();
        }
    }

    private static async ValueTask<IMemoryOwner<byte>> BufferRequestBodyAsync(HttpRequest request)
    {
        request.EnableBuffering();
        if (request.ContentLength is > 0 and <= int.MaxValue)
        {
            var length = (int)request.ContentLength.Value;
            var owner = MemoryPool<byte>.Shared.Rent(length);
            var memory = owner.Memory[..length];
            var read = 0;
            while (read < length)
            {
                var n = await request.Body.ReadAsync(memory[read..]);
                if (n == 0)
                {
                    break;
                }

                read += n;
            }

            request.Body.Position = 0;
            return new SlicedOwner(owner, read);
        }

        using var ms = new MemoryStream(capacity: 4096);
        await request.Body.CopyToAsync(ms);
        request.Body.Position = 0;
        var bytes = ms.ToArray();
        var rented = MemoryPool<byte>.Shared.Rent(bytes.Length);
        bytes.CopyTo(rented.Memory);
        return new SlicedOwner(rented, bytes.Length);
    }

    private static string ResolveOrganizationId(HttpContext context, ReadOnlyMemory<byte> body)
    {
        var fromHeader = FirstHeader(context, DeliveryHeaders.DemoOrganizationId);
        if (!string.IsNullOrWhiteSpace(fromHeader))
        {
            return fromHeader;
        }

        if (body.Length == 0)
        {
            return "org-demo-001";
        }

        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("organizationId", out var org)
                && org.ValueKind == JsonValueKind.String)
            {
                var text = org.GetString();
                if (!string.IsNullOrWhiteSpace(text))
                {
                    return text;
                }
            }
        }
        catch (JsonException)
        {
        }

        return "org-demo-001";
    }

    private static string? FirstHeader(HttpContext context, string name) =>
        context.Request.Headers.TryGetValue(name, out var values) && values.Count > 0
            ? values[0]
            : null;

    private sealed class SlicedOwner(IMemoryOwner<byte> inner, int length) : IMemoryOwner<byte>
    {
        public Memory<byte> Memory => inner.Memory[..length];
        public void Dispose() => inner.Dispose();
    }
}

public sealed class StaticCapabilityMiddleware
{
    private readonly RequestDelegate _next;

    public StaticCapabilityMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(
        HttpContext context,
        OffersRouteRegistryAccessor offersAccessor,
        RequestTraceService traces)
    {
        if (!HttpMethods.IsGet(context.Request.Method)
            || !context.Request.Path.StartsWithSegments("/internal/capabilities"))
        {
            await _next(context);
            return;
        }

        var path = context.Request.Path.Value ?? string.Empty;
        var capability = offersAccessor.Registry.CurrentModule.Capabilities
            .FirstOrDefault(c => PathTemplate.Matches(c.PathTemplate, path));
        if (capability is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        var started = Environment.TickCount64;
        var correlationId = context.Request.Headers.TryGetValue(DeliveryHeaders.CorrelationId, out var values)
            ? values.FirstOrDefault()
            : null;

        var payload = StaticCapabilityRenderer.Render(capability, path);
        context.Response.StatusCode = StatusCodes.Status200OK;
        context.Response.ContentType = "application/json; charset=utf-8";
        await JsonSerializer.SerializeAsync(context.Response.Body, payload, cancellationToken: context.RequestAborted);

        traces.RecordSimple(
            "capability",
            capability.Method,
            path,
            capability.TargetService,
            capability.TargetUrl,
            correlationId,
            "200",
            Environment.TickCount64 - started,
            path);
    }
}
