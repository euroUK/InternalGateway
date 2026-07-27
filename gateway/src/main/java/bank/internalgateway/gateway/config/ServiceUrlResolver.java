package bank.internalgateway.gateway.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ServiceUrlResolver {

    private static final String DEPOSIT_OFFER_SERVICE = "deposit-offer-service";

    private final Map<String, String> serviceUrls;

    public ServiceUrlResolver(GatewayProperties properties) {
        this.serviceUrls = buildServiceUrls(properties);
    }

    public String resolve(String serviceName) {
        String url = serviceUrls.get(serviceName);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "No base URL configured for service '" + serviceName
                            + "'. Add gateway.services." + serviceName + " in application.yml");
        }
        return stripTrailingSlash(url);
    }

    public Map<String, String> configuredServices() {
        return Map.copyOf(serviceUrls);
    }

    private Map<String, String> buildServiceUrls(GatewayProperties properties) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (properties.services() != null) {
            properties.services().forEach((name, url) -> {
                if (url != null && !url.isBlank()) {
                    urls.put(name, url);
                }
            });
        }
        if (!urls.containsKey(DEPOSIT_OFFER_SERVICE) && properties.depositOfferServiceUrl() != null) {
            urls.put(DEPOSIT_OFFER_SERVICE, properties.depositOfferServiceUrl());
        }
        return urls;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
