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

    public IdentityEnvelopeService(GatewayProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.envelopeSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createBankUserEnvelope(String subjectId, String organizationId, String correlationId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("internal-gateway")
                .subject(subjectId)
                .claim("organizationId", organizationId)
                .claim("correlationId", correlationId)
                .claim("operationId", UUID.randomUUID().toString())
                .claim("businessControlEvidenceId", "poc-stub-passed")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30)))
                .signWith(secretKey)
                .compact();
    }

    public String createDeliveryEnvelope(Map<String, Object> claims) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer("internal-gateway")
                .claim("deliveryMode", "signed-delivery-envelope")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30)))
                .signWith(secretKey);
        claims.forEach(builder::claim);
        return builder.compact();
    }
}
