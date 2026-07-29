using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using InternalGateway.DotNet.Identity;
using InternalGateway.DotNet.Observability;
using Microsoft.Extensions.Options;

namespace InternalGateway.DotNet.Messaging;

public sealed class ConfigurableEventMapper(EventMappingRegistry mappingRegistry)
{
    public CanonicalInboundEvent Map(
        string mappingConfigFile,
        IReadOnlyDictionary<string, string> sourceHeaders,
        string sourcePayloadJson)
    {
        var mapping = mappingRegistry.GetMapping(mappingConfigFile);
        if (mapping is null || (mapping.LegacyDetection?.Matches(sourceHeaders) ?? false))
        {
            return MapPassThrough(sourceHeaders, sourcePayloadJson, mapping is null ? "mapping missing" : "legacy");
        }

        using var body = JsonDocument.Parse(string.IsNullOrWhiteSpace(sourcePayloadJson) ? "{}" : sourcePayloadJson);
        var canonicalHeaders = MapHeaders(sourceHeaders, mapping.HeaderMapping);
        var sourceMessageType = SourceHeaderForTarget(sourceHeaders, mapping.HeaderMapping, "eventType")
            ?? Get(sourceHeaders, "messageType")
            ?? Get(sourceHeaders, "eventType");

        var canonicalEventType = sourceMessageType is null
            ? null
            : mapping.EventTypeMapping.GetValueOrDefault(sourceMessageType, sourceMessageType);
        if (canonicalEventType is not null)
        {
            canonicalHeaders["eventType"] = canonicalEventType;
        }

        var payload = MapBodyFields(body.RootElement, mapping);
        foreach (var (key, value) in canonicalHeaders)
        {
            payload.TryAdd(key, value);
        }

        var eventId = Get(canonicalHeaders, "eventId");
        var eventType = Get(canonicalHeaders, "eventType");
        var occurredAt = Get(canonicalHeaders, "occurredAt") ?? DateTimeOffset.UtcNow.ToString("O");
        payload.TryAdd("occurredAt", occurredAt);

        return new CanonicalInboundEvent(
            eventId,
            eventType,
            occurredAt,
            payload,
            sourceMessageType,
            $"{sourceMessageType}->{canonicalEventType}");
    }

    private static CanonicalInboundEvent MapPassThrough(
        IReadOnlyDictionary<string, string> headers,
        string payloadJson,
        string reason)
    {
        var payload = new Dictionary<string, object?>(StringComparer.Ordinal);
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(payloadJson) ? "{}" : payloadJson);
            foreach (var prop in doc.RootElement.EnumerateObject())
            {
                payload[prop.Name] = JsonElementToObject(prop.Value);
            }
        }
        catch (JsonException)
        {
        }

        foreach (var (k, v) in headers)
        {
            payload.TryAdd(k, v);
        }

        return new CanonicalInboundEvent(
            Get(headers, "eventId") ?? Get(headers, "messageId"),
            Get(headers, "eventType") ?? Get(headers, "messageType"),
            Get(headers, "occurredAt") ?? DateTimeOffset.UtcNow.ToString("O"),
            payload,
            Get(headers, "messageType") ?? Get(headers, "eventType"),
            reason);
    }

    private static Dictionary<string, string> MapHeaders(
        IReadOnlyDictionary<string, string> source,
        Dictionary<string, string> mapping)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (from, to) in mapping)
        {
            if (source.TryGetValue(from, out var value) && !string.IsNullOrWhiteSpace(value))
            {
                result[to] = value;
            }
        }

        return result;
    }

    private static Dictionary<string, object?> MapBodyFields(JsonElement body, ParsedEventMapping mapping)
    {
        var payload = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var (from, to) in mapping.BodyFieldMapping)
        {
            if (TryGetPath(body, from, out var value))
            {
                payload[to] = JsonElementToObject(value);
            }
        }

        foreach (var (target, rule) in mapping.Transforms)
        {
            if (!payload.TryGetValue(rule.From, out var raw) && !TryGetPath(body, rule.From, out var element))
            {
                continue;
            }

            object? sourceValue = raw;
            if (sourceValue is null && TryGetPath(body, rule.From, out var el))
            {
                sourceValue = JsonElementToObject(el);
            }

            if (string.Equals(rule.Rule, "percent-to-decimal", StringComparison.OrdinalIgnoreCase)
                && TryToDouble(sourceValue, out var percent))
            {
                payload[target] = Math.Round(percent / 100.0, rule.Scale);
            }
        }

        return payload;
    }

    private static bool TryGetPath(JsonElement root, string path, out JsonElement value)
    {
        value = default;
        var current = root;
        foreach (var segment in path.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(segment, out current))
            {
                return false;
            }
        }

        value = current;
        return true;
    }

    private static string? SourceHeaderForTarget(
        IReadOnlyDictionary<string, string> source,
        Dictionary<string, string> mapping,
        string target)
    {
        foreach (var (from, to) in mapping)
        {
            if (to.Equals(target, StringComparison.Ordinal) && source.TryGetValue(from, out var value))
            {
                return value;
            }
        }

        return null;
    }

    private static string? Get(IReadOnlyDictionary<string, string> map, string key) =>
        map.TryGetValue(key, out var value) ? value : null;

    private static object? JsonElementToObject(JsonElement element) => element.ValueKind switch
    {
        JsonValueKind.String => element.GetString(),
        JsonValueKind.Number when element.TryGetInt64(out var l) => l,
        JsonValueKind.Number => element.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Null => null,
        _ => element.GetRawText()
    };

    private static bool TryToDouble(object? value, out double number)
    {
        switch (value)
        {
            case double d:
                number = d;
                return true;
            case float f:
                number = f;
                return true;
            case int i:
                number = i;
                return true;
            case long l:
                number = l;
                return true;
            case string s:
                return double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out number);
            default:
                number = 0;
                return false;
        }
    }
}

public sealed class EventDedupCache
{
    private readonly int _maximumSize;
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, long>> _caches = new(StringComparer.Ordinal);
    private long _hits;

    public EventDedupCache(IOptions<GatewayOptions> options) =>
        _maximumSize = options.Value.Dedup.MaximumSize;

    public bool IsDuplicate(string bindingId, TimeSpan ttl, string? dedupValue)
    {
        if (string.IsNullOrWhiteSpace(dedupValue))
        {
            return false;
        }

        var cache = _caches.GetOrAdd(bindingId, _ => new ConcurrentDictionary<string, long>(StringComparer.Ordinal));
        var expiresAt = Environment.TickCount64 + (long)ttl.TotalMilliseconds;
        if (!cache.TryAdd(dedupValue, expiresAt))
        {
            Interlocked.Increment(ref _hits);
            return true;
        }

        if (cache.Count > _maximumSize)
        {
            foreach (var key in cache.Keys.Take(cache.Count - _maximumSize))
            {
                cache.TryRemove(key, out _);
            }
        }

        return false;
    }

    public long Hits => Interlocked.Read(ref _hits);
}

public sealed class GatewayRateLimiter
{
    private readonly ConsumeBindingRegistry _bindings;
    private readonly ConcurrentDictionary<string, TokenBucket> _buckets = new(StringComparer.Ordinal);

    public GatewayRateLimiter(ConsumeBindingRegistry bindings) => _bindings = bindings;

    public bool TryAcquire(string? profileName, string scopeKey)
    {
        if (string.IsNullOrWhiteSpace(profileName)
            || !_bindings.RateLimitPolicies.TryGetValue(profileName, out var policy)
            || policy.SustainedPerSecond >= double.MaxValue / 4)
        {
            return true;
        }

        var bucket = _buckets.GetOrAdd(profileName + ":" + scopeKey, _ => new TokenBucket(policy));
        return bucket.TryAcquire(policy);
    }

    private sealed class TokenBucket
    {
        private double _tokens;
        private long _lastRefillNanos = Environment.TickCount64;

        public TokenBucket(RateLimitPolicy policy) => _tokens = policy.Burst;

        public bool TryAcquire(RateLimitPolicy policy)
        {
            lock (this)
            {
                var now = Environment.TickCount64;
                var elapsed = (now - _lastRefillNanos) / 1000.0;
                if (elapsed > 0)
                {
                    _tokens = Math.Min(policy.Burst, _tokens + elapsed * policy.SustainedPerSecond);
                    _lastRefillNanos = now;
                }

                if (_tokens < 1)
                {
                    return false;
                }

                _tokens -= 1;
                return true;
            }
        }
    }
}

public sealed class ResilienceDeliveryExecutor(
    IHttpClientFactory httpClientFactory,
    ConsumeBindingRegistry bindings,
    IOptions<GatewayOptions> options)
{
    public async Task<DeliveryResult> DeliverAsync(
        string resilienceProfile,
        string method,
        string targetUrl,
        string payloadJson,
        Action<HttpRequestMessage> configure,
        CancellationToken cancellationToken)
    {
        var profile = bindings.ResilienceProfiles.GetValueOrDefault(resilienceProfile)
            ?? new ResilienceProfile(resilienceProfile, TimeSpan.FromSeconds(2), 3);
        var client = httpClientFactory.CreateClient("upstream");
        Exception? lastError = null;
        int? lastStatus = null;
        string? lastDetail = null;

        for (var attempt = 1; attempt <= profile.MaxAttempts; attempt++)
        {
            try
            {
                using var request = new HttpRequestMessage(new HttpMethod(method), targetUrl)
                {
                    Content = new StringContent(payloadJson, System.Text.Encoding.UTF8, "application/json")
                };
                configure(request);
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                cts.CancelAfter(profile.Timeout);
                using var response = await client.SendAsync(request, cts.Token);
                lastStatus = (int)response.StatusCode;
                if ((int)response.StatusCode is >= 200 and < 300)
                {
                    return new DeliveryResult(true, lastStatus, attempt, null);
                }

                lastDetail = await response.Content.ReadAsStringAsync(cancellationToken);
                if (!ShouldRetry(lastStatus.Value) || attempt == profile.MaxAttempts)
                {
                    return new DeliveryResult(false, lastStatus, attempt, lastDetail);
                }
            }
            catch (Exception ex) when (attempt < profile.MaxAttempts)
            {
                lastError = ex;
                lastDetail = ex.Message;
            }

            var delay = options.Value.Resilience.BackoffBaseMs
                + Random.Shared.NextInt64(0, Math.Max(1, options.Value.Resilience.BackoffJitterMs));
            await Task.Delay(TimeSpan.FromMilliseconds(delay), cancellationToken);
        }

        return new DeliveryResult(false, lastStatus, profile.MaxAttempts, lastDetail ?? lastError?.Message);
    }

    private static bool ShouldRetry(int status) => status is 502 or 503 or 504;

    public sealed record DeliveryResult(bool Success, int? StatusCode, int Attempts, string? Detail);
}

public sealed class EventFanOutService(
    ConsumeBindingRegistry bindings,
    IOptions<GatewayOptions> options,
    IdentityEnvelopeService identity,
    EventDedupCache dedupCache,
    GatewayRateLimiter rateLimiter,
    ResilienceDeliveryExecutor deliveryExecutor,
    RequestTraceService traces)
{
    public async Task DeliverAsync(string bindingId, CanonicalInboundEvent eventData, CancellationToken cancellationToken)
    {
        var binding = bindings.FindById(bindingId)
            ?? throw new InvalidOperationException("Unknown consume binding: " + bindingId);

        var dedupField = ConsumeBindingRegistry.DedupFieldFromKey(binding.DedupKey);
        var dedupValue = ResolveClaim(eventData, dedupField);
        if (dedupCache.IsDuplicate(bindingId, binding.DedupTtl, dedupValue))
        {
            traces.RecordSimple(
                "messaging",
                "KAFKA",
                binding.InboundKafkaPath,
                "—",
                "(dedup skipped)",
                eventData.EventId,
                "DEDUP",
                0,
                eventData.MappingSummary);
            return;
        }

        var routes = bindings.ResolveRoutes(bindingId, eventData.EventType ?? string.Empty);
        if (routes.Count == 0)
        {
            throw new InvalidOperationException(
                $"No fan-out route for binding '{bindingId}' and eventType '{eventData.EventType}'");
        }

        var payloadJson = JsonSerializer.Serialize(eventData.Payload);
        Exception? lastError = null;
        foreach (var route in routes)
        {
            try
            {
                await DeliverToRouteAsync(binding, route, eventData, payloadJson, cancellationToken);
            }
            catch (Exception ex)
            {
                lastError = ex;
            }
        }

        if (lastError is not null)
        {
            throw lastError;
        }
    }

    private async Task DeliverToRouteAsync(
        ConsumeBinding binding,
        FanOutRoute route,
        CanonicalInboundEvent eventData,
        string payloadJson,
        CancellationToken cancellationToken)
    {
        var rateScope = binding.BindingId + ":" + route.TargetId;
        if (!rateLimiter.TryAcquire(route.RateLimitProfile, rateScope))
        {
            traces.RecordSimple(
                "messaging",
                route.Method,
                binding.InboundKafkaPath,
                route.Service,
                "(rate limited)",
                eventData.EventId,
                "RATE_LIMIT",
                0,
                "profile=" + route.RateLimitProfile);
            return;
        }

        var targetUrl = options.Value.DepositOfferServiceUrl.TrimEnd('/') + route.Path;
        var claims = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var claim in route.EnvelopeClaims)
        {
            var value = ResolveClaimObject(eventData, claim);
            if (value is not null)
            {
                claims[claim] = value;
            }
        }

        var envelope = identity.CreateDeliveryEnvelope(claims);
        var started = Environment.TickCount64;
        var result = await deliveryExecutor.DeliverAsync(
            route.ResilienceProfile,
            route.Method,
            targetUrl,
            payloadJson,
            request =>
            {
                request.Headers.TryAddWithoutValidation(DeliveryHeaders.DeliveryEnvelope, envelope);
                if (eventData.EventId is not null)
                {
                    request.Headers.TryAddWithoutValidation(DeliveryHeaders.EventId, eventData.EventId);
                }

                if (eventData.EventType is not null)
                {
                    request.Headers.TryAddWithoutValidation(DeliveryHeaders.EventType, eventData.EventType);
                }
            },
            cancellationToken);

        traces.RecordSimple(
            "messaging",
            route.Method,
            binding.InboundKafkaPath + " [" + eventData.SourceMessageType + "→" + eventData.EventType + "]",
            route.Service,
            targetUrl,
            eventData.EventId,
            result.StatusCode?.ToString() ?? (result.Success ? "SUCCESS" : "ERROR"),
            Environment.TickCount64 - started,
            result.Success ? eventData.MappingSummary : result.Detail);

        if (!result.Success)
        {
            throw new InvalidOperationException(
                $"Delivery failed after {result.Attempts} attempt(s): {result.Detail}");
        }
    }

    private static string? ResolveClaim(CanonicalInboundEvent eventData, string claim) =>
        ResolveClaimObject(eventData, claim)?.ToString();

    private static object? ResolveClaimObject(CanonicalInboundEvent eventData, string claim) => claim switch
    {
        "eventId" => eventData.EventId,
        "eventType" => eventData.EventType,
        "occurredAt" => eventData.OccurredAt,
        _ => eventData.Payload.GetValueOrDefault(claim)
    };
}
