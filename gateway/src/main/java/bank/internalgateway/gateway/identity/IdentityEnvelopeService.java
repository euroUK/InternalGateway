package bank.internalgateway.gateway.identity;

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
    private final String issuer;
    private final int ttlSeconds;
    private final String businessControlEvidenceId;

    public IdentityEnvelopeService(GatewayProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.envelopeSecret().getBytes(StandardCharsets.UTF_8));
        GatewayProperties.Envelope envelope = properties.envelope();
        this.issuer = envelope != null && envelope.issuer() != null ? envelope.issuer() : "internal-gateway";
        this.ttlSeconds = envelope != null && envelope.ttlSeconds() != null && envelope.ttlSeconds() > 0
                ? envelope.ttlSeconds()
                : 30;
        this.businessControlEvidenceId = envelope != null && envelope.businessControlEvidenceId() != null
                ? envelope.businessControlEvidenceId()
                : "poc-stub-passed";
    }

    public String createBankUserEnvelope(String subjectId, String organizationId, String correlationId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(subjectId)
                .claim("organizationId", organizationId)
                .claim("correlationId", correlationId)
                .claim("operationId", UUID.randomUUID().toString())
                .claim("businessControlEvidenceId", businessControlEvidenceId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey)
                .compact();
    }

    public String createDeliveryEnvelope(Map<String, Object> claims) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(issuer)
                .claim("deliveryMode", "signed-delivery-envelope")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey);
        claims.forEach(builder::claim);
        return builder.compact();
    }
}
