using System.Collections.Concurrent;
using Microsoft.Extensions.Options;

namespace InternalGateway.DotNet.Observability;

public sealed class RequestTraceService
{
    private readonly int _maxEntries;
    private readonly int _topRoutesLimit;
    private readonly ConcurrentQueue<RequestTraceEntry> _entries = new();
    private long _totalRecorded;

    public RequestTraceService(IOptions<GatewayOptions> options)
    {
        _maxEntries = options.Value.Observability.TraceBufferSize;
        _topRoutesLimit = options.Value.Observability.TopRoutesLimit;
    }

    public void RecordSimple(
        string plane,
        string method,
        string inboundPath,
        string? targetService,
        string? targetUrl,
        string? correlationId,
        string status,
        long durationMs,
        string? detail)
    {
        Record(new RequestTraceEntry(
            Guid.NewGuid().ToString("D"),
            DateTimeOffset.UtcNow,
            plane,
            method,
            inboundPath,
            targetService,
            targetUrl,
            correlationId,
            status,
            durationMs,
            detail));
    }

    public void Record(RequestTraceEntry entry)
    {
        _entries.Enqueue(entry);
        Interlocked.Increment(ref _totalRecorded);
        while (_entries.Count > _maxEntries && _entries.TryDequeue(out _))
        {
        }
    }

    public IReadOnlyList<RequestTraceEntry> Recent(int limit)
    {
        var take = Math.Max(1, limit);
        return _entries.Reverse().Take(take).ToArray();
    }

    public RequestStats Stats()
    {
        var snapshot = _entries.ToArray();
        var byRoute = snapshot
            .GroupBy(e => e.Method + " " + e.InboundPath, StringComparer.Ordinal)
            .Select(g => new RouteStat(g.Key, g.LongCount()))
            .OrderByDescending(r => r.Count)
            .Take(_topRoutesLimit)
            .ToArray();

        double Avg(Func<RequestTraceEntry, bool> pred)
        {
            var filtered = snapshot.Where(pred).Select(e => (double)e.DurationMs).ToArray();
            return filtered.Length == 0 ? 0 : filtered.Average();
        }

        return new RequestStats(
            Interlocked.Read(ref _totalRecorded),
            snapshot.Length,
            Avg(_ => true),
            Avg(e => e.Plane == "ingress"),
            Avg(e => e.Plane == "capability"),
            Avg(e => e.Plane == "messaging"),
            byRoute);
    }
}

public sealed record RequestTraceEntry(
    string Id,
    DateTimeOffset Timestamp,
    string Plane,
    string Method,
    string InboundPath,
    string? TargetService,
    string? TargetUrl,
    string? CorrelationId,
    string Status,
    long DurationMs,
    string? Detail);

public sealed record RouteStat(string Route, long Count);

public sealed record RequestStats(
    long TotalRecorded,
    int BufferedEntries,
    double AvgDurationMs,
    double AvgIngressMs,
    double AvgCapabilityMs,
    double AvgMessagingMs,
    IReadOnlyList<RouteStat> TopRoutes);
