package bank.internalgateway.gateway.identity;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.gateway.config.GatewayProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityEnvelopeService {

    private final SecretKey secretKey;
    private final String fallbackIssuer;
    private final int fallbackTtlSeconds;
    private final String fallbackEvidenceId;
    private final BenchmarkRouteRegistry benchmarkRouteRegistry;

    public IdentityEnvelopeService(GatewayProperties properties, BenchmarkRouteRegistry benchmarkRouteRegistry) {
        this.secretKey = Keys.hmacShaKeyFor(properties.envelopeSecret().getBytes(StandardCharsets.UTF_8));
        GatewayProperties.Envelope envelope = properties.envelope();
        this.fallbackIssuer = envelope != null && envelope.issuer() != null ? envelope.issuer() : "internal-gateway";
        this.fallbackTtlSeconds = envelope != null && envelope.ttlSeconds() != null && envelope.ttlSeconds() > 0
                ? envelope.ttlSeconds()
                : 30;
        this.fallbackEvidenceId = envelope != null && envelope.businessControlEvidenceId() != null
                ? envelope.businessControlEvidenceId()
                : "poc-stub-passed";
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
    }

    public String createBankUserEnvelope(String subjectId, String organizationId, String correlationId) {
        return createBankUserEnvelope(subjectId, organizationId, correlationId, null);
    }

    public String createBankUserEnvelope(
            String subjectId,
            String organizationId,
            String correlationId,
            String evidenceIdOverride) {
        CompiledBenchmarkModule.EnvelopePolicy policy = envelopePolicy();
        String evidenceId = evidenceIdOverride != null && !evidenceIdOverride.isBlank()
                ? evidenceIdOverride
                : policy.businessControlEvidenceId();
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(policy.issuer())
                .subject(subjectId)
                .claim("organizationId", organizationId)
                .claim("correlationId", correlationId)
                .claim("operationId", UUID.randomUUID().toString())
                .claim("businessControlEvidenceId", evidenceId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(policy.ttlSeconds())))
                .signWith(secretKey)
                .compact();
    }

    public String createDeliveryEnvelope(Map<String, Object> claims) {
        CompiledBenchmarkModule.EnvelopePolicy policy = envelopePolicy();
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(policy.issuer())
                .claim("deliveryMode", "signed-delivery-envelope")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(policy.ttlSeconds())))
                .signWith(secretKey);
        claims.forEach(builder::claim);
        return builder.compact();
    }

    private CompiledBenchmarkModule.EnvelopePolicy envelopePolicy() {
        try {
            return benchmarkRouteRegistry.currentModule().envelopePolicy();
        } catch (IllegalStateException ex) {
            return new CompiledBenchmarkModule.EnvelopePolicy(
                    fallbackIssuer, fallbackTtlSeconds, java.util.List.of(), fallbackEvidenceId);
        }
    }
}
