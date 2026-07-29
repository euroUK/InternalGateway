using System.Text.Json;

namespace InternalGateway.DotNet.Dsl;

public static class EnrichedResponseMapper
{
    public static Dictionary<string, object?> Map(
        IReadOnlyDictionary<string, string> fieldMap,
        JsonElement request,
        JsonElement adapter,
        JsonElement target,
        string correlationId)
    {
        var result = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var (key, expression) in fieldMap)
        {
            result[key] = Resolve(expression, request, adapter, target, correlationId);
        }

        return result;
    }

    private static object? Resolve(
        string expression,
        JsonElement request,
        JsonElement adapter,
        JsonElement target,
        string correlationId)
    {
        if (string.IsNullOrWhiteSpace(expression))
        {
            return null;
        }

        if (expression == "envelope.correlationId")
        {
            return correlationId;
        }

        if (expression == "target")
        {
            return ToObject(target);
        }

        if (expression.StartsWith("request.", StringComparison.Ordinal))
        {
            return Lookup(request, expression["request.".Length..]);
        }

        if (expression.StartsWith("adapter.", StringComparison.Ordinal))
        {
            return Lookup(adapter, expression["adapter.".Length..]);
        }

        if (expression.StartsWith("target.", StringComparison.Ordinal))
        {
            return Lookup(target, expression["target.".Length..]);
        }

        throw new ArgumentException("Unsupported responseMapping expression: " + expression);
    }

    private static object? Lookup(JsonElement source, string field)
    {
        if (source.ValueKind != JsonValueKind.Object
            || !source.TryGetProperty(field, out var value))
        {
            return null;
        }

        return ToObject(value);
    }

    private static object? ToObject(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Object => value.EnumerateObject()
            .ToDictionary(p => p.Name, p => ToObject(p.Value), StringComparer.Ordinal),
        JsonValueKind.Array => value.EnumerateArray().Select(ToObject).ToList(),
        JsonValueKind.String => value.GetString(),
        JsonValueKind.Number when value.TryGetInt64(out var l) => l,
        JsonValueKind.Number => value.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Null => null,
        _ => value.ToString()
    };
}
