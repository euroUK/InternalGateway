using System.Text.RegularExpressions;

namespace InternalGateway.DotNet.Dsl;

public static partial class PathTemplate
{
    [GeneratedRegex(@"\{([a-zA-Z0-9_]+)\}")]
    private static partial Regex VariableRegex();

    public static bool Matches(string template, string path) => Extract(template, path) is not null;

    public static Dictionary<string, string>? Extract(string template, string path)
    {
        var names = new List<string>();
        var regex = new System.Text.StringBuilder("^");
        var last = 0;
        foreach (Match match in VariableRegex().Matches(template))
        {
            regex.Append(Regex.Escape(template[last..match.Index]));
            names.Add(match.Groups[1].Value);
            regex.Append("([^/]+)");
            last = match.Index + match.Length;
        }

        regex.Append(Regex.Escape(template[last..]));
        regex.Append('$');

        var pathMatch = Regex.Match(path, regex.ToString());
        if (!pathMatch.Success)
        {
            return null;
        }

        var values = new Dictionary<string, string>(names.Count, StringComparer.Ordinal);
        for (var i = 0; i < names.Count; i++)
        {
            values[names[i]] = pathMatch.Groups[i + 1].Value;
        }

        return values;
    }

    public static string Expand(string template, IReadOnlyDictionary<string, string> values)
    {
        ArgumentNullException.ThrowIfNull(template);
        var last = 0;
        var expanded = new System.Text.StringBuilder();
        foreach (Match match in VariableRegex().Matches(template))
        {
            expanded.Append(template.AsSpan(last, match.Index - last));
            var name = match.Groups[1].Value;
            if (values is null || !values.TryGetValue(name, out var value) || string.IsNullOrWhiteSpace(value))
            {
                throw new ArgumentException("Missing path variable: " + name);
            }

            expanded.Append(value);
            last = match.Index + match.Length;
        }

        expanded.Append(template.AsSpan(last));
        return expanded.ToString();
    }
}

public static class StaticCapabilityRenderer
{
    public static Dictionary<string, object?> Render(CompiledCapabilityRoute capability, string requestPath)
    {
        var pathVars = PathTemplate.Extract(capability.PathTemplate, requestPath)
            ?? throw new ArgumentException("Path does not match capability template: " + requestPath);

        var rendered = new Dictionary<string, object?>(capability.ResponseTemplate.Count, StringComparer.Ordinal);
        foreach (var (key, value) in capability.ResponseTemplate)
        {
            rendered[key] = Substitute(value, pathVars);
        }

        return rendered;
    }

    private static object? Substitute(object? value, Dictionary<string, string> pathVars)
    {
        if (value is not string text)
        {
            return value;
        }

        if (text == "{now}")
        {
            return DateTimeOffset.UtcNow.ToString("O");
        }

        var result = text;
        foreach (var (name, pathValue) in pathVars)
        {
            result = result.Replace("{" + name + "}", pathValue, StringComparison.Ordinal);
        }

        return result;
    }
}

public sealed class OffersRouteRegistry
{
    private readonly string _dslDirectory;
    private Snapshot? _current;
    private Action<CompiledOffersModule>? _reloadListener;

    public OffersRouteRegistry(string dslDirectory)
    {
        _dslDirectory = dslDirectory;
    }

    public void SetReloadListener(Action<CompiledOffersModule>? listener) => _reloadListener = listener;

    public CompiledOffersModule LoadInitial()
    {
        var module = OffersModuleCompiler.CompileFromDirectory(_dslDirectory);
        _current = new Snapshot(module, DateTimeOffset.UtcNow, 1, "loaded");
        return module;
    }

    public ReloadResult Reload()
    {
        var previous = _current;
        try
        {
            var module = OffersModuleCompiler.CompileFromDirectory(_dslDirectory);
            var nextVersion = previous?.Version + 1 ?? 1;
            var snapshot = new Snapshot(module, DateTimeOffset.UtcNow, nextVersion, "reloaded");
            _current = snapshot;
            _reloadListener?.Invoke(module);
            return ReloadResult.Ok(snapshot, previous);
        }
        catch (Exception ex)
        {
            return ReloadResult.Fail(previous, ex.Message);
        }
    }

    public CompiledOffersModule CurrentModule =>
        _current?.Module ?? throw new InvalidOperationException("Offers DSL registry is not initialized");

    public Snapshot CurrentSnapshot =>
        _current ?? throw new InvalidOperationException("Offers DSL registry is not initialized");

    public sealed record Snapshot(CompiledOffersModule Module, DateTimeOffset LoadedAt, int Version, string Status);

    public sealed record ReloadResult(bool Success, Snapshot? Current, Snapshot? Previous, string? Error)
    {
        public static ReloadResult Ok(Snapshot current, Snapshot? previous) => new(true, current, previous, null);
        public static ReloadResult Fail(Snapshot? previous, string error) => new(false, previous, previous, error);
    }
}
