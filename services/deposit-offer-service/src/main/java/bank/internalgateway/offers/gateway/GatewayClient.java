package bank.internalgateway.offers.gateway;

import bank.internalgateway.offers.config.OfferServiceProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class GatewayClient {

    private final OfferServiceProperties properties;
    private final RestClient restClient;

    public GatewayClient(OfferServiceProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @SuppressWarnings("unchecked")
    public AccountDepositContext fetchAccountDepositContext(String accountId, String correlationId) {
        Map<String, Object> response = restClient.get()
                .uri(properties.gatewayUrl() + "/internal/capabilities/accounts/{accountId}/deposit-context", accountId)
                .headers(headers -> applyCorrelation(headers, correlationId))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("Empty account deposit context from gateway");
        }
        return new AccountDepositContext(
                (String) response.get("accountId"),
                (String) response.get("currency"),
                new BigDecimal(response.get("availableBalance").toString()),
                Boolean.TRUE.equals(response.get("debitAllowed"))
        );
    }

    @SuppressWarnings("unchecked")
    public String fetchOrganizationDisplayName(String organizationId, String correlationId) {
        Map<String, Object> response = restClient.get()
                .uri(properties.gatewayUrl() + "/internal/capabilities/organizations/{organizationId}/display-info", organizationId)
                .headers(headers -> applyCorrelation(headers, correlationId))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            return organizationId;
        }
        Object displayName = response.get("displayName");
        return displayName != null ? displayName.toString() : organizationId;
    }

    private void applyCorrelation(org.springframework.http.HttpHeaders headers, String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set("X-Correlation-Id", correlationId);
        }
    }

    public record AccountDepositContext(
            String accountId,
            String currency,
            BigDecimal availableBalance,
            boolean debitAllowed
    ) {
    }
}
