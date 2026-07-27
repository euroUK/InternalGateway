package bank.internalgateway.offers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "offer-service")
public record OfferServiceProperties(String gatewayUrl) {
}
