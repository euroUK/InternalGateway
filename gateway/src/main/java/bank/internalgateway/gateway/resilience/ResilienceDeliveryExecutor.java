package bank.internalgateway.gateway.resilience;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Consumer;

@Component
public class ResilienceDeliveryExecutor {

    private final RestClient restClient;
    private final ResilienceProfileRegistry profileRegistry;
    private final ResilienceMetricsService metricsService;

    public ResilienceDeliveryExecutor(
            RestClient restClient,
            ResilienceProfileRegistry profileRegistry,
            ResilienceMetricsService metricsService) {
        this.restClient = restClient;
        this.profileRegistry = profileRegistry;
        this.metricsService = metricsService;
    }

    public DeliveryResult deliver(
            String resilienceProfileName,
            String method,
            String targetUrl,
            String payloadJson,
            Consumer<RestClient.RequestBodySpec> requestCustomizer) {

        ResilienceProfile profile = profileRegistry.getProfile(resilienceProfileName);
        int attempt = 0;
        Exception lastError = null;

        while (attempt < profile.maxAttempts()) {
            attempt++;
            try {
                RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(method))
                        .uri(targetUrl)
                        .contentType(MediaType.APPLICATION_JSON);
                if (requestCustomizer != null) {
                    requestCustomizer.accept(request);
                }
                request.body(payloadJson).retrieve().toBodilessEntity();
                if (attempt > 1) {
                    metricsService.recordRetrySuccess();
                }
                return new DeliveryResult(true, attempt, null, null);
            } catch (RestClientResponseException ex) {
                lastError = ex;
                if (profile.shouldRetry(attempt, ex.getStatusCode().value(), false)) {
                    metricsService.recordRetryAttempt();
                    sleep(profile.backoffMillis(attempt));
                    continue;
                }
                metricsService.recordRetryFailure();
                return new DeliveryResult(false, attempt, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (ResourceAccessException ex) {
                lastError = ex;
                if (profile.shouldRetry(attempt, 0, true)) {
                    metricsService.recordRetryAttempt();
                    sleep(profile.backoffMillis(attempt));
                    continue;
                }
                metricsService.recordRetryFailure();
                return new DeliveryResult(false, attempt, null, ex.getMessage());
            }
        }

        metricsService.recordRetryFailure();
        if (lastError instanceof RestClientResponseException responseException) {
            return new DeliveryResult(
                    false,
                    attempt,
                    responseException.getStatusCode().value(),
                    responseException.getResponseBodyAsString());
        }
        return new DeliveryResult(false, attempt, null, lastError != null ? lastError.getMessage() : "delivery failed");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record DeliveryResult(
            boolean success,
            int attempts,
            Integer statusCode,
            String detail
    ) {
    }
}
