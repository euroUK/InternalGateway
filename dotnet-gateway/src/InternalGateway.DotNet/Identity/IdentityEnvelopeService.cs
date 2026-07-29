using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace InternalGateway.DotNet.Identity;

public sealed class IdentityEnvelopeService
{
    private readonly GatewayOptions _options;
    private readonly OffersRouteRegistryAccessor _offers;
    private readonly SigningCredentials _credentials;
    private readonly JsonWebTokenHandler _handler = new();

    public IdentityEnvelopeService(IOptions<GatewayOptions> options, OffersRouteRegistryAccessor offers)
    {
        _options = options.Value;
        _offers = offers;
        var keyBytes = Encoding.UTF8.GetBytes(_options.EnvelopeSecret);
        if (keyBytes.Length < 32)
        {
            Array.Resize(ref keyBytes, 32);
        }

        _credentials = new SigningCredentials(
            new SymmetricSecurityKey(keyBytes),
            SecurityAlgorithms.HmacSha256);
    }

    public string CreateBankUserEnvelope(
        string subjectId,
        string organizationId,
        string correlationId,
        string? evidenceIdOverride = null)
    {
        var policy = TryEnvelopePolicy();
        var evidenceId = !string.IsNullOrWhiteSpace(evidenceIdOverride)
            ? evidenceIdOverride
            : policy.BusinessControlEvidenceId;
        var now = DateTime.UtcNow;
        var descriptor = new SecurityTokenDescriptor
        {
            Issuer = policy.Issuer,
            Subject = new System.Security.Claims.ClaimsIdentity(
            [
                new System.Security.Claims.Claim(JwtRegisteredClaimNames.Sub, subjectId),
                new System.Security.Claims.Claim("organizationId", organizationId),
                new System.Security.Claims.Claim("correlationId", correlationId),
                new System.Security.Claims.Claim("operationId", Guid.NewGuid().ToString("D")),
                new System.Security.Claims.Claim("businessControlEvidenceId", evidenceId)
            ]),
            NotBefore = now,
            Expires = now.AddSeconds(policy.TtlSeconds),
            SigningCredentials = _credentials
        };
        return _handler.CreateToken(descriptor);
    }

    public string CreateDeliveryEnvelope(IReadOnlyDictionary<string, object?> claims)
    {
        var policy = TryEnvelopePolicy();
        var now = DateTime.UtcNow;
        var identity = new System.Security.Claims.ClaimsIdentity();
        identity.AddClaim(new System.Security.Claims.Claim("deliveryMode", "signed-delivery-envelope"));
        foreach (var (key, value) in claims)
        {
            if (value is not null)
            {
                identity.AddClaim(new System.Security.Claims.Claim(key, Convert.ToString(value) ?? string.Empty));
            }
        }

        var descriptor = new SecurityTokenDescriptor
        {
            Issuer = policy.Issuer,
            Subject = identity,
            NotBefore = now,
            Expires = now.AddSeconds(policy.TtlSeconds),
            SigningCredentials = _credentials
        };
        return _handler.CreateToken(descriptor);
    }

    private Dsl.EnvelopePolicy TryEnvelopePolicy()
    {
        try
        {
            return _offers.Registry.CurrentModule.EnvelopePolicy;
        }
        catch
        {
            return new Dsl.EnvelopePolicy(
                _options.Envelope.Issuer,
                _options.Envelope.TtlSeconds,
                [],
                _options.Envelope.BusinessControlEvidenceId);
        }
    }
}

/// <summary>Late-bound accessor so identity can be constructed before registry load completes in DI.</summary>
public sealed class OffersRouteRegistryAccessor
{
    public Dsl.OffersRouteRegistry Registry { get; set; } = null!;
}
