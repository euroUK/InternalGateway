using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace InternalGateway.DotNet.Dsl;

public static class OffersModuleCompiler
{
    public const string OffersDslFile = "deposit-offers-gateway.dsl.yaml";

    public static CompiledOffersModule CompileFromDirectory(string dslDir)
    {
        var path = Path.Combine(dslDir, OffersDslFile);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException("Offers DSL not found: " + path);
        }

        using var reader = File.OpenText(path);
        var root = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build()
            .Deserialize<Dictionary<object, object>>(reader)
            ?? throw new InvalidOperationException("Offers DSL root must be a mapping");

        return Compile(ToStringKeyed(root));
    }

    public static CompiledOffersModule Compile(Dictionary<string, object?> root)
    {
        if (root.Count == 0)
        {
            throw new ArgumentException("Offers DSL is empty");
        }

        var metadata = AsMap(root.GetValueOrDefault("metadata"));
        var moduleName = GetString(metadata, "name") ?? "deposit-offers";
        var moduleVersion = GetString(metadata, "version") ?? "1.0.0";

        var identity = AsMap(root.GetValueOrDefault("identity"));
        var forwarded = AsMap(identity?.GetValueOrDefault("forwardedEnvelope"));
        var issuer = GetString(forwarded, "issuer") ?? "internal-gateway";
        var ttlSeconds = ParseTtlSeconds(forwarded?.GetValueOrDefault("ttl"), 30);
        var claims = ParseClaims(forwarded);
        if (claims.Count == 0)
        {
            claims = ["subjectId", "organizationId", "correlationId", "operationId", "businessControlEvidenceId"];
        }

        var capabilities = CompileCapabilities(root);
        var ingressRoutes = CompileIngressRoutes(root, capabilities);
        var evidenceId = ingressRoutes
            .Select(r => r.BusinessControlEvidenceId)
            .FirstOrDefault(id => !string.IsNullOrWhiteSpace(id))
            ?? "poc-stub-passed";

        Validate(ingressRoutes, capabilities);
        return new CompiledOffersModule(
            moduleName,
            moduleVersion,
            new EnvelopePolicy(issuer, ttlSeconds, claims, evidenceId),
            ingressRoutes,
            capabilities);
    }

    private static List<CompiledIngressRoute> CompileIngressRoutes(
        Dictionary<string, object?> root,
        List<CompiledCapabilityRoute> capabilities)
    {
        if (root.GetValueOrDefault("routes") is not List<object> routes || routes.Count == 0)
        {
            throw new ArgumentException("offers DSL routes[] must not be empty");
        }

        var capabilitiesById = capabilities.ToDictionary(c => c.CapabilityId, StringComparer.Ordinal);
        var result = new List<CompiledIngressRoute>(routes.Count);
        foreach (var item in routes)
        {
            var route = AsMap(item);
            if (route is null)
            {
                continue;
            }

            var routeId = Required(route, "id");
            var request = RequiredMap(route, "request");
            var target = RequiredMap(route, "target");
            var validation = AsMap(route.GetValueOrDefault("validation"));
            var businessControl = AsMap(validation?.GetValueOrDefault("businessControl"));
            var method = Required(request, "method").ToUpperInvariant();
            var inboundPath = Required(request, "path");
            var targetService = Required(target, "service");
            var targetMethod = (GetString(target, "method") ?? method).ToUpperInvariant();
            var targetPath = Required(target, "path");
            var stub = businessControl is null
                || string.Equals(GetString(businessControl, "effect"), "stub", StringComparison.OrdinalIgnoreCase);
            var evidenceId = GetString(businessControl, "evidenceId") ?? "poc-stub-passed";
            var adapter = CompileAdapter(routeId, AsMap(route.GetValueOrDefault("adapter")), capabilitiesById);
            var responseMapping = CompileResponseMapping(route.GetValueOrDefault("responseMapping"));
            if (adapter is not null && responseMapping.Count == 0)
            {
                throw new ArgumentException("Route " + routeId + " with adapter requires responseMapping");
            }

            result.Add(new CompiledIngressRoute(
                routeId,
                method,
                inboundPath,
                GetString(route, "identityContext") ?? "bankUser",
                targetService,
                targetMethod,
                targetPath,
                stub,
                evidenceId,
                adapter,
                responseMapping));
        }

        if (result.Count == 0)
        {
            throw new ArgumentException("offers DSL routes[] must not be empty");
        }

        return result;
    }

    private static CompiledAdapterStep? CompileAdapter(
        string routeId,
        Dictionary<string, object?>? adapter,
        Dictionary<string, CompiledCapabilityRoute> capabilitiesById)
    {
        if (adapter is null || adapter.Count == 0)
        {
            return null;
        }

        var capabilityId = Required(adapter, "capability");
        if (!capabilitiesById.TryGetValue(capabilityId, out var capability))
        {
            throw new ArgumentException("Route " + routeId + " adapter.capability not found: " + capabilityId);
        }

        return new CompiledAdapterStep(capabilityId, capability.Method, capability.PathTemplate);
    }

    private static IReadOnlyDictionary<string, string> CompileResponseMapping(object? mappingObj)
    {
        var mapping = AsMap(mappingObj);
        if (mapping is null || mapping.Count == 0)
        {
            return new Dictionary<string, string>(StringComparer.Ordinal);
        }

        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (key, value) in mapping)
        {
            var text = Convert.ToString(value);
            if (string.IsNullOrWhiteSpace(text))
            {
                throw new ArgumentException("responseMapping." + key + " must be a string");
            }

            result[key] = text;
        }

        return result;
    }

    private static List<CompiledCapabilityRoute> CompileCapabilities(Dictionary<string, object?> root)
    {
        if (root.GetValueOrDefault("capabilities") is not List<object> capabilities || capabilities.Count == 0)
        {
            throw new ArgumentException("offers DSL capabilities[] must not be empty");
        }

        var result = new List<CompiledCapabilityRoute>(capabilities.Count);
        foreach (var item in capabilities)
        {
            var capability = AsMap(item);
            if (capability is null)
            {
                continue;
            }

            var request = RequiredMap(capability, "request");
            var responseTemplate = AsMap(capability.GetValueOrDefault("responseTemplate"))
                ?? throw new ArgumentException("capability requires responseTemplate");
            if (responseTemplate.Count == 0)
            {
                throw new ArgumentException("capability requires responseTemplate");
            }

            result.Add(new CompiledCapabilityRoute(
                Required(capability, "id"),
                Required(request, "method").ToUpperInvariant(),
                Required(request, "path"),
                GetString(capability, "executionMode") ?? "static-stub",
                new Dictionary<string, object?>(responseTemplate, StringComparer.Ordinal),
                GetString(capability, "targetService"),
                GetString(capability, "targetUrl")));
        }

        if (result.Count == 0)
        {
            throw new ArgumentException("offers DSL capabilities[] must not be empty");
        }

        return result;
    }

    private static void Validate(
        List<CompiledIngressRoute> ingressRoutes,
        List<CompiledCapabilityRoute> capabilities)
    {
        var routeIds = new HashSet<string>(StringComparer.Ordinal);
        var routeKeys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var route in ingressRoutes)
        {
            if (!routeIds.Add(route.RouteId))
            {
                throw new ArgumentException("Duplicate route id: " + route.RouteId);
            }

            var key = route.Method + " " + route.InboundPath;
            if (!routeKeys.Add(key))
            {
                throw new ArgumentException("Duplicate ingress route: " + key);
            }

            if (!route.TargetPath.StartsWith('/'))
            {
                throw new ArgumentException("Route " + route.RouteId + " target.path must start with /");
            }
        }

        var capabilityIds = new HashSet<string>(StringComparer.Ordinal);
        var capabilityKeys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var capability in capabilities)
        {
            if (!capabilityIds.Add(capability.CapabilityId))
            {
                throw new ArgumentException("Duplicate capability id: " + capability.CapabilityId);
            }

            var key = capability.Method + " " + capability.PathTemplate;
            if (!capabilityKeys.Add(key))
            {
                throw new ArgumentException("Duplicate capability route: " + key);
            }

            if (!string.Equals(capability.ExecutionMode, "static-stub", StringComparison.Ordinal))
            {
                throw new ArgumentException("Unsupported capability executionMode: " + capability.ExecutionMode);
            }
        }
    }

    public static Dictionary<string, object?> ToStringKeyed(Dictionary<object, object> source)
    {
        var result = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var (key, value) in source)
        {
            result[Convert.ToString(key) ?? string.Empty] = Normalize(value);
        }

        return result;
    }

    public static object? Normalize(object? value) => value switch
    {
        Dictionary<object, object> map => ToStringKeyed(map),
        List<object> list => list.Select(Normalize).Cast<object>().ToList(),
        _ => value
    };

    public static Dictionary<string, object?>? AsMap(object? value) =>
        value switch
        {
            Dictionary<string, object?> typed => typed,
            Dictionary<object, object> untyped => ToStringKeyed(untyped),
            _ => null
        };

    public static string? GetString(Dictionary<string, object?>? map, string key)
    {
        if (map is null || !map.TryGetValue(key, out var value) || value is null)
        {
            return null;
        }

        return Convert.ToString(value);
    }

    public static string Required(Dictionary<string, object?> map, string key)
    {
        var value = GetString(map, key);
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("Missing required field: " + key);
        }

        return value;
    }

    public static Dictionary<string, object?> RequiredMap(Dictionary<string, object?> map, string key) =>
        AsMap(map.GetValueOrDefault(key))
        ?? throw new ArgumentException("Missing required object: " + key);

    public static int ParseTtlSeconds(object? value, int defaultValue)
    {
        if (value is int i)
        {
            return i;
        }

        if (value is long l)
        {
            return (int)l;
        }

        var text = Convert.ToString(value);
        if (string.IsNullOrWhiteSpace(text))
        {
            return defaultValue;
        }

        if (text.EndsWith('s') && int.TryParse(text[..^1], out var seconds))
        {
            return seconds;
        }

        return int.TryParse(text, out var plain) ? plain : defaultValue;
    }

    public static List<string> ParseClaims(Dictionary<string, object?>? forwarded)
    {
        if (forwarded?.GetValueOrDefault("claims") is not List<object> claims)
        {
            return [];
        }

        return claims.Select(c => Convert.ToString(c) ?? string.Empty).Where(s => s.Length > 0).ToList();
    }

    public static TimeSpan ParseDuration(object? value, TimeSpan defaultValue)
    {
        var text = Convert.ToString(value);
        if (string.IsNullOrWhiteSpace(text))
        {
            return defaultValue;
        }

        if (text.EndsWith("ms", StringComparison.OrdinalIgnoreCase)
            && double.TryParse(text[..^2], out var ms))
        {
            return TimeSpan.FromMilliseconds(ms);
        }

        if (text.EndsWith('s') && double.TryParse(text[..^1], out var seconds))
        {
            return TimeSpan.FromSeconds(seconds);
        }

        if (text.EndsWith('m') && double.TryParse(text[..^1], out var minutes))
        {
            return TimeSpan.FromMinutes(minutes);
        }

        if (text.EndsWith('h') && double.TryParse(text[..^1], out var hours))
        {
            return TimeSpan.FromHours(hours);
        }

        if (text.EndsWith('d') && double.TryParse(text[..^1], out var days))
        {
            return TimeSpan.FromDays(days);
        }

        return TimeSpan.TryParse(text, out var parsed) ? parsed : defaultValue;
    }

    public static double ParseRate(object? value, double defaultValue)
    {
        var text = Convert.ToString(value);
        if (string.IsNullOrWhiteSpace(text))
        {
            return defaultValue;
        }

        var slash = text.IndexOf('/');
        if (slash > 0 && double.TryParse(text[..slash], out var rate))
        {
            return rate;
        }

        return double.TryParse(text, out var plain) ? plain : defaultValue;
    }
}
