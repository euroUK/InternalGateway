package bank.internalgateway.gateway.test;

import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.messaging.CanonicalInboundEvent;
import bank.internalgateway.gateway.messaging.ConfigurableEventMapper;
import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import bank.internalgateway.gateway.messaging.EventFanOutService;
import bank.internalgateway.gateway.resilience.EventDedupCache;
import bank.internalgateway.gateway.resilience.GatewayRateLimiter;
import bank.internalgateway.gateway.resilience.ResilienceMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TestScenarioService {

    private final TestProcessorRegistry testProcessorRegistry;
    private final EventFanOutService eventFanOutService;
    private final ConfigurableEventMapper eventMapper;
    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final ServiceUrlResolver serviceUrlResolver;
    private final EventDedupCache dedupCache;
    private final GatewayRateLimiter rateLimiter;
    private final ResilienceMetricsService metricsService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TestScenarioService(
            TestProcessorRegistry testProcessorRegistry,
            EventFanOutService eventFanOutService,
            ConfigurableEventMapper eventMapper,
            ConsumeBindingRegistry consumeBindingRegistry,
            ServiceUrlResolver serviceUrlResolver,
            EventDedupCache dedupCache,
            GatewayRateLimiter rateLimiter,
            ResilienceMetricsService metricsService,
            RestClient restClient,
            ObjectMapper objectMapper) {
        this.testProcessorRegistry = testProcessorRegistry;
        this.eventFanOutService = eventFanOutService;
        this.eventMapper = eventMapper;
        this.consumeBindingRegistry = consumeBindingRegistry;
        this.serviceUrlResolver = serviceUrlResolver;
        this.dedupCache = dedupCache;
        this.rateLimiter = rateLimiter;
        this.metricsService = metricsService;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> runDedupScenario(String bindingId) throws Exception {
        ConsumeBindingRegistry.ConsumeBinding binding = consumeBindingRegistry.findById(bindingId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown binding: " + bindingId));
        CanonicalInboundEvent event = sampleEvent(binding.mappingFile(), "dedup-" + UUID.randomUUID());
        eventFanOutService.deliver(bindingId, event);
        eventFanOutService.deliver(bindingId, event);
        return Map.of(
                "scenario", "dedup",
                "bindingId", bindingId,
                "eventId", event.eventId(),
                "expected", "first delivery succeeds, second is DEDUP"
        );
    }

    public Map<String, Object> runRetryScenario(String bindingId, int failCount) {
        configureOfferServiceFault(failCount, 503);
        TestProcessorRegistry.TestProcessorRegistration processor = requireTestProcessor(bindingId);
        String offerId = "retry-" + UUID.randomUUID();
        publishViaTestProcessor(processor, offerId);
        return Map.of(
                "scenario", "retry",
                "bindingId", bindingId,
                "processorOfferId", offerId,
                "injectedFailures", failCount,
                "expected", "Gateway retries delivery until offer-service accepts"
        );
    }

    public Map<String, Object> runRateLimitScenario(String bindingId, int burstCount) {
        TestProcessorRegistry.TestProcessorRegistration processor = requireTestProcessor(bindingId);
        for (int i = 0; i < burstCount; i++) {
            publishViaTestProcessor(processor, "rate-" + UUID.randomUUID());
        }
        return Map.of(
                "scenario", "rate-limit",
                "bindingId", bindingId,
                "burstCount", burstCount,
                "expected", "Some deliveries are marked RATE_LIMIT in gateway trace"
        );
    }

    public Map<String, Object> resilienceStats() {
        return Map.of(
                "dedup", dedupCache.cacheSizes(),
                "dedupHits", dedupCache.dedupHits(),
                "rateLimitBuckets", rateLimiter.snapshot(),
                "metrics", metricsService.snapshot(dedupCache, rateLimiter)
        );
    }

    public List<TestProcessorRegistry.TestProcessorRegistration> registeredProcessors() {
        return testProcessorRegistry.all();
    }

    public TestProcessorRegistry.TestProcessorRegistration registerProcessor(
            TestProcessorRegistry.TestProcessorRegistration registration) {
        return testProcessorRegistry.register(registration);
    }

    private TestProcessorRegistry.TestProcessorRegistration requireTestProcessor(String bindingId) {
        return testProcessorRegistry.all().stream()
                .filter(item -> item.bindingId().equals(bindingId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No test processor registered for binding " + bindingId));
    }

    private void configureOfferServiceFault(int failCount, int statusCode) {
        String offerServiceUrl = serviceUrlResolver.resolve("deposit-offer-service");
        restClient.post()
                .uri(offerServiceUrl + "/internal/test/fault")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("failNextRequests", failCount, "statusCode", statusCode))
                .retrieve()
                .toBodilessEntity();
    }

    private void publishViaTestProcessor(TestProcessorRegistry.TestProcessorRegistration processor, String offerId) {
        String url = processor.demoBaseUrl() + "/demo/publish-offer-event";
        Map<String, Object> body = Map.of(
                "eventType", "OFFER_CREATED",
                "processorOfferId", offerId,
                "processorOfferVersion", 1,
                "productCode", "TERM-TEST",
                "rate", 10.0,
                "termMonths", 10,
                "minAmount", 1000,
                "maxAmount", 1000000,
                "currency", "RUB"
        );
        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private CanonicalInboundEvent sampleEvent(String mappingFile, String eventId) throws Exception {
        Map<String, String> headers = Map.of(
                "messageType", "OFFER_CREATED",
                "messageId", eventId,
                "offerExternalId", "offer-" + eventId,
                "publishedAt", java.time.Instant.now().toString()
        );
        String payload = objectMapper.writeValueAsString(Map.of(
                "offerExternalId", "offer-" + eventId,
                "revision", 1,
                "productExternalCode", "TERM-TEST",
                "interestRatePercent", 10.0,
                "durationMonths", 10,
                "amountRange", Map.of("from", 1000, "to", 1000000),
                "currencyCode", "RUB"
        ));
        return eventMapper.map(mappingFile, headers, payload);
    }
}
