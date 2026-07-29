using System.Collections.Concurrent;
using System.Text.Json;
using InternalGateway.DotNet.Dsl;
using Microsoft.Extensions.Options;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace InternalGateway.DotNet.Messaging;

public sealed record CanonicalInboundEvent(
    string? EventId,
    string? EventType,
    string? OccurredAt,
    Dictionary<string, object?> Payload,
    string? SourceMessageType,
    string MappingSummary);

public sealed record FanOutRoute(
    string BindingId,
    string TargetId,
    string Service,
    string Method,
    string Path,
    IReadOnlyList<string> EnvelopeClaims,
    string ResilienceProfile,
    string? RateLimitProfile);

public sealed record FanOutTarget(
    string TargetId,
    HashSet<string> AllowedEventTypes,
    string Service,
    string Method,
    string? DefaultPath,
    Dictionary<string, string> PathByEventType,
    IReadOnlyList<string> EnvelopeClaims,
    string ResilienceProfile,
    string? RateLimitProfile)
{
    public bool MatchesEventType(string eventType) =>
        AllowedEventTypes.Count == 0 || AllowedEventTypes.Contains(eventType);

    public string? ResolvePath(string eventType) =>
        PathByEventType.TryGetValue(eventType, out var path) ? path : DefaultPath;
}

public sealed record ConsumeBinding(
    string BindingId,
    string PhysicalTopic,
    string ConsumerGroup,
    string? MappingFile,
    string DedupKey,
    TimeSpan DedupTtl,
    IReadOnlyList<FanOutTarget> FanOutTargets)
{
    public string InboundKafkaPath => "kafka://" + PhysicalTopic;
}

public sealed class ConsumeBindingRegistry
{
    private readonly GatewayOptions _options;
    private Dictionary<string, ConsumeBinding> _byId = new(StringComparer.Ordinal);

    public ConsumeBindingRegistry(IOptions<GatewayOptions> options) => _options = options.Value;

    public void Load(string dslDirectory)
    {
        var path = Path.Combine(dslDirectory, "deposit-messaging-gateway.dsl.yaml");
        using var reader = File.OpenText(path);
        var root = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build()
            .Deserialize<Dictionary<object, object>>(reader);
        var map = OffersModuleCompiler.ToStringKeyed(root ?? new Dictionary<object, object>());
        var physicalTopics = ResolvePhysicalTopics(map);
        var byId = new Dictionary<string, ConsumeBinding>(StringComparer.Ordinal);

        var messaging = OffersModuleCompiler.AsMap(map.GetValueOrDefault("messaging"));
        if (messaging?.GetValueOrDefault("consumeBindings") is List<object> bindings)
        {
            foreach (var item in bindings)
            {
                var binding = ParseBinding(OffersModuleCompiler.AsMap(item), physicalTopics);
                if (binding is not null)
                {
                    byId[binding.BindingId] = binding;
                }
            }
        }

        var rateLimits = OffersModuleCompiler.AsMap(
            OffersModuleCompiler.AsMap(map.GetValueOrDefault("profiles"))?.GetValueOrDefault("rateLimits"));
        RateLimitPolicies = ParseRateLimits(rateLimits);

        var resilience = OffersModuleCompiler.AsMap(
            OffersModuleCompiler.AsMap(map.GetValueOrDefault("profiles"))?.GetValueOrDefault("resilience"));
        ResilienceProfiles = ParseResilience(resilience);

        _byId = byId;
    }

    public IReadOnlyDictionary<string, RateLimitPolicy> RateLimitPolicies { get; private set; } =
        new Dictionary<string, RateLimitPolicy>(StringComparer.Ordinal);

    public IReadOnlyDictionary<string, ResilienceProfile> ResilienceProfiles { get; private set; } =
        new Dictionary<string, ResilienceProfile>(StringComparer.Ordinal);

    public ConsumeBinding? FindById(string bindingId) =>
        _byId.TryGetValue(bindingId, out var binding) ? binding : null;

    public IReadOnlyList<ConsumeBinding> AllBindings() => _byId.Values.ToArray();

    public IReadOnlyList<FanOutRoute> ResolveRoutes(string bindingId, string eventType)
    {
        if (!_byId.TryGetValue(bindingId, out var binding))
        {
            return [];
        }

        var routes = new List<FanOutRoute>();
        foreach (var target in binding.FanOutTargets)
        {
            if (!target.MatchesEventType(eventType))
            {
                continue;
            }

            var path = target.ResolvePath(eventType);
            if (string.IsNullOrWhiteSpace(path))
            {
                continue;
            }

            routes.Add(new FanOutRoute(
                binding.BindingId,
                target.TargetId,
                target.Service,
                target.Method,
                path,
                target.EnvelopeClaims,
                target.ResilienceProfile,
                target.RateLimitProfile));
        }

        return routes;
    }

    private ConsumeBinding? ParseBinding(
        Dictionary<string, object?>? bindingMap,
        Dictionary<string, string> physicalTopics)
    {
        if (bindingMap is null)
        {
            return null;
        }

        var id = OffersModuleCompiler.GetString(bindingMap, "id");
        if (string.IsNullOrWhiteSpace(id))
        {
            return null;
        }

        var source = OffersModuleCompiler.AsMap(bindingMap.GetValueOrDefault("source"));
        var topicAlias = OffersModuleCompiler.GetString(source, "topicAlias") ?? string.Empty;
        var physicalTopic = physicalTopics.GetValueOrDefault(topicAlias) ?? topicAlias;
        var consumerGroup = OffersModuleCompiler.GetString(source, "consumerGroup")
            ?? ("internal-gateway.dotnet." + id);
        var normalization = OffersModuleCompiler.AsMap(bindingMap.GetValueOrDefault("normalization"));
        var mappingFile = OffersModuleCompiler.GetString(normalization, "mappingFile");
        var dedup = OffersModuleCompiler.AsMap(bindingMap.GetValueOrDefault("deduplication"));
        var dedupKey = OffersModuleCompiler.GetString(dedup, "key") ?? "header://eventId";
        var dedupTtl = OffersModuleCompiler.ParseDuration(
            dedup?.GetValueOrDefault("ttl"),
            _options.Dedup.DefaultTtl);

        var fanOut = OffersModuleCompiler.AsMap(bindingMap.GetValueOrDefault("fanOut"));
        var targets = new List<FanOutTarget>();
        if (fanOut?.GetValueOrDefault("targets") is List<object> targetList)
        {
            foreach (var targetObj in targetList)
            {
                var parsed = ParseTarget(OffersModuleCompiler.AsMap(targetObj));
                if (parsed is not null)
                {
                    targets.Add(parsed);
                }
            }
        }

        return new ConsumeBinding(id, physicalTopic, consumerGroup, mappingFile, dedupKey, dedupTtl, targets);
    }

    private static FanOutTarget? ParseTarget(Dictionary<string, object?>? target)
    {
        if (target is null)
        {
            return null;
        }

        var filter = OffersModuleCompiler.AsMap(target.GetValueOrDefault("filter"));
        var allowed = new HashSet<string>(StringComparer.Ordinal);
        if (filter?.GetValueOrDefault("eventTypeAny") is List<object> any)
        {
            foreach (var item in any)
            {
                var text = Convert.ToString(item);
                if (!string.IsNullOrWhiteSpace(text))
                {
                    allowed.Add(text);
                }
            }
        }

        var delivery = OffersModuleCompiler.AsMap(target.GetValueOrDefault("delivery")) ?? target;
        var pathByEventType = new Dictionary<string, string>(StringComparer.Ordinal);
        if (OffersModuleCompiler.AsMap(delivery.GetValueOrDefault("pathByEventType")) is { } paths)
        {
            foreach (var (key, value) in paths)
            {
                var path = Convert.ToString(value);
                if (!string.IsNullOrWhiteSpace(path))
                {
                    pathByEventType[key] = path;
                }
            }
        }

        var envelope = OffersModuleCompiler.AsMap(delivery.GetValueOrDefault("identityEnvelope"));
        var claims = new List<string>();
        if (envelope?.GetValueOrDefault("claims") is List<object> claimList)
        {
            foreach (var claim in claimList)
            {
                var text = Convert.ToString(claim);
                if (!string.IsNullOrWhiteSpace(text))
                {
                    claims.Add(text);
                }
            }
        }

        var resilience = OffersModuleCompiler.GetString(delivery, "resilienceProfile")
            ?? "internalEventDelivery";

        return new FanOutTarget(
            OffersModuleCompiler.GetString(target, "id") ?? "target",
            allowed,
            OffersModuleCompiler.GetString(delivery, "service") ?? "deposit-offer-service",
            OffersModuleCompiler.GetString(delivery, "method") ?? "POST",
            OffersModuleCompiler.GetString(delivery, "path"),
            pathByEventType,
            claims,
            resilience,
            OffersModuleCompiler.GetString(delivery, "rateLimitProfile"));
    }

    private static Dictionary<string, string> ResolvePhysicalTopics(Dictionary<string, object?> root)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        var providerSets = OffersModuleCompiler.AsMap(root.GetValueOrDefault("providerSets"));
        if (providerSets is null)
        {
            return result;
        }

        foreach (var set in providerSets.Values)
        {
            var setMap = OffersModuleCompiler.AsMap(set);
            var providers = OffersModuleCompiler.AsMap(setMap?.GetValueOrDefault("providers"));
            if (providers is null)
            {
                continue;
            }

            foreach (var provider in providers.Values)
            {
                var topics = OffersModuleCompiler.AsMap(
                    OffersModuleCompiler.AsMap(provider)?.GetValueOrDefault("topics"));
                if (topics is null)
                {
                    continue;
                }

                foreach (var (alias, topicObj) in topics)
                {
                    var topicMap = OffersModuleCompiler.AsMap(topicObj);
                    var physical = OffersModuleCompiler.GetString(topicMap, "physicalTopic");
                    if (!string.IsNullOrWhiteSpace(physical))
                    {
                        result[alias] = physical;
                    }
                }
            }
        }

        return result;
    }

    private static Dictionary<string, RateLimitPolicy> ParseRateLimits(Dictionary<string, object?>? map)
    {
        var result = new Dictionary<string, RateLimitPolicy>(StringComparer.Ordinal);
        if (map is null)
        {
            return result;
        }

        foreach (var (name, value) in map)
        {
            var policy = OffersModuleCompiler.AsMap(value);
            result[name] = new RateLimitPolicy(
                name,
                OffersModuleCompiler.ParseRate(policy?.GetValueOrDefault("sustained"), double.MaxValue / 2),
                (int)OffersModuleCompiler.ParseRate(policy?.GetValueOrDefault("burst"), 1));
        }

        return result;
    }

    private static Dictionary<string, ResilienceProfile> ParseResilience(Dictionary<string, object?>? map)
    {
        var result = new Dictionary<string, ResilienceProfile>(StringComparer.Ordinal);
        if (map is null)
        {
            return result;
        }

        foreach (var (name, value) in map)
        {
            var profile = OffersModuleCompiler.AsMap(value);
            var retries = OffersModuleCompiler.AsMap(profile?.GetValueOrDefault("retries"));
            var maxAttempts = 1;
            if (retries?.GetValueOrDefault("maxAttempts") is int i)
            {
                maxAttempts = i;
            }
            else if (retries?.GetValueOrDefault("maxAttempts") is long l)
            {
                maxAttempts = (int)l;
            }

            var timeout = OffersModuleCompiler.ParseDuration(profile?.GetValueOrDefault("timeout"), TimeSpan.FromSeconds(2));
            result[name] = new ResilienceProfile(name, timeout, Math.Max(1, maxAttempts));
        }

        return result;
    }

    public static string DedupFieldFromKey(string dedupKey)
    {
        const string prefix = "header://";
        return dedupKey.StartsWith(prefix, StringComparison.Ordinal)
            ? dedupKey[prefix.Length..]
            : dedupKey;
    }
}

public sealed record RateLimitPolicy(string Name, double SustainedPerSecond, int Burst);

public sealed record ResilienceProfile(string Name, TimeSpan Timeout, int MaxAttempts);

public sealed class EventMappingRegistry
{
    private readonly Dictionary<string, ParsedEventMapping> _mappings = new(StringComparer.Ordinal);

    public void Load(string dslDirectory)
    {
        foreach (var file in Directory.EnumerateFiles(dslDirectory, "*-event-mapping.yaml"))
        {
            LoadFile(Path.GetFileName(file), file);
        }

        foreach (var file in Directory.EnumerateFiles(dslDirectory, "*event-mapping.yaml"))
        {
            LoadFile(Path.GetFileName(file), file);
        }
    }

    private void LoadFile(string name, string path)
    {
        using var reader = File.OpenText(path);
        var root = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build()
            .Deserialize<Dictionary<object, object>>(reader);
        var map = OffersModuleCompiler.ToStringKeyed(root ?? new Dictionary<object, object>());
        _mappings[name] = ParsedEventMapping.From(map);
    }

    public ParsedEventMapping? GetMapping(string fileName) =>
        _mappings.TryGetValue(fileName, out var mapping) ? mapping : null;
}

public sealed class ParsedEventMapping
{
    public required Dictionary<string, string> HeaderMapping { get; init; }
    public required Dictionary<string, string> EventTypeMapping { get; init; }
    public required Dictionary<string, string> BodyFieldMapping { get; init; }
    public required Dictionary<string, TransformRule> Transforms { get; init; }
    public LegacyDetection? LegacyDetection { get; init; }

    public static ParsedEventMapping From(Dictionary<string, object?> map)
    {
        static Dictionary<string, string> StringMap(object? value)
        {
            var result = new Dictionary<string, string>(StringComparer.Ordinal);
            var source = OffersModuleCompiler.AsMap(value);
            if (source is null)
            {
                return result;
            }

            foreach (var (k, v) in source)
            {
                var text = Convert.ToString(v);
                if (!string.IsNullOrWhiteSpace(text))
                {
                    result[k] = text;
                }
            }

            return result;
        }

        var transforms = new Dictionary<string, TransformRule>(StringComparer.Ordinal);
        var transformMap = OffersModuleCompiler.AsMap(map.GetValueOrDefault("transforms"));
        if (transformMap is not null)
        {
            foreach (var (target, ruleObj) in transformMap)
            {
                var rule = OffersModuleCompiler.AsMap(ruleObj);
                transforms[target] = new TransformRule(
                    OffersModuleCompiler.GetString(rule, "from") ?? target,
                    OffersModuleCompiler.GetString(rule, "rule") ?? "identity",
                    rule?.GetValueOrDefault("scale") is int scale ? scale :
                    rule?.GetValueOrDefault("scale") is long scaleL ? (int)scaleL : 6);
            }
        }

        LegacyDetection? legacy = null;
        var legacyMap = OffersModuleCompiler.AsMap(map.GetValueOrDefault("legacyDetection"));
        if (legacyMap is not null)
        {
            legacy = new LegacyDetection(
                ToStringList(legacyMap.GetValueOrDefault("headersPresent")),
                ToStringList(legacyMap.GetValueOrDefault("headersAbsent")));
        }

        return new ParsedEventMapping
        {
            HeaderMapping = StringMap(map.GetValueOrDefault("headerMapping")),
            EventTypeMapping = StringMap(map.GetValueOrDefault("eventTypeMapping")),
            BodyFieldMapping = StringMap(map.GetValueOrDefault("bodyFieldMapping")),
            Transforms = transforms,
            LegacyDetection = legacy
        };
    }

    private static List<string> ToStringList(object? value)
    {
        if (value is not List<object> list)
        {
            return [];
        }

        return list.Select(v => Convert.ToString(v) ?? string.Empty).Where(s => s.Length > 0).ToList();
    }
}

public sealed record TransformRule(string From, string Rule, int Scale);

public sealed record LegacyDetection(IReadOnlyList<string> HeadersPresent, IReadOnlyList<string> HeadersAbsent)
{
    public bool Matches(IReadOnlyDictionary<string, string> headers)
    {
        foreach (var required in HeadersPresent)
        {
            if (!headers.ContainsKey(required))
            {
                return false;
            }
        }

        foreach (var absent in HeadersAbsent)
        {
            if (headers.ContainsKey(absent))
            {
                return false;
            }
        }

        return HeadersPresent.Count > 0 || HeadersAbsent.Count > 0;
    }
}
