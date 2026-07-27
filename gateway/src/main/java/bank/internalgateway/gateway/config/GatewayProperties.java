package bank.internalgateway.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String dslPath,
        String depositOfferServiceUrl,
        String envelopeSecret,
        String corsAllowedOrigins,
        Map<String, String> services,
        Kafka kafka
) {
    public record Kafka(
            String processorTopic,
            String consumerGroup,
            String activeBindingId
    ) {
    }
}
