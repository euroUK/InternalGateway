package bank.internalgateway.scg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scg")
public record ScgGatewayProperties(
        String dslPath,
        String envelopeSecret,
        String depositOfferServiceUrl,
        String selfBaseUrl,
        Envelope envelope,
        Observability observability
) {
    public record Envelope(
            String issuer,
            Integer ttlSeconds,
            String businessControlEvidenceId
    ) {
    }

    public record Observability(
            int traceBufferSize,
            int topRoutesLimit
    ) {
    }
}
