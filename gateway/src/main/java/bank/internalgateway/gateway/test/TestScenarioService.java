package bank.internalgateway.gateway.test;

import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.messaging.CanonicalInboundEvent;
import bank.internalgateway.gateway.messaging.ConfigurableEventMapper;
import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import bank.internalgateway.gateway.messaging.EventFanOutService;
import bank.internalgateway.gateway.resilience.EventDedupCache;
import bank.internalgateway.gateway.resilience.GatewayRateLimiter;
import bank.internalgateway.gateway.resilience.ResilienceMetricsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TestScenarioService {

    private static final String OFFER_CREATED_PUBLISH_FIXTURE = "test-fixtures/offer-created-publish.json";
    private static final String OFFER_CREATED_PAYLOAD_FIXTURE = "test-fixtures/offer-created-payload.json";

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
    private final GatewayProperties properties;

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
            ObjectMapper objectMapper,
            GatewayProperties properties) {
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
        this.properties = properties;
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
        configureOfferServiceFault(failCount, defaultFaultStatus());
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
                .uri(offerServiceUrl + faultPath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("failNextRequests", failCount, "statusCode", statusCode))
                .retrieve()
                .toBodilessEntity();
    }

    private void publishViaTestProcessor(TestProcessorRegistry.TestProcessorRegistration processor, String offerId) {
        String url = processor.demoBaseUrl() + demoPublishPath();
        Map<String, Object> body = loadFixture(OFFER_CREATED_PUBLISH_FIXTURE);
        body = new LinkedHashMap<>(body);
        body.put("processorOfferId", offerId);
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
                "publishedAt", Instant.now().toString()
        );
        Map<String, Object> payload = loadFixture(OFFER_CREATED_PAYLOAD_FIXTURE);
        payload = new LinkedHashMap<>(payload);
        payload.put("offerExternalId", "offer-" + eventId);
        String payloadJson = objectMapper.writeValueAsString(payload);
        return eventMapper.map(mappingFile, headers, payloadJson);
    }

    private Map<String, Object> loadFixture(String classpathLocation) {
        try {
            JsonNode node = objectMapper.readTree(new ClassPathResource(classpathLocation).getInputStream());
            return objectMapper.convertValue(node, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load test fixture: " + classpathLocation, ex);
        }
    }

    private String faultPath() {
        return properties.testHarness() != null && properties.testHarness().faultPath() != null
                ? properties.testHarness().faultPath()
                : "/internal/test/fault";
    }

    private String demoPublishPath() {
        return properties.testHarness() != null && properties.testHarness().demoPublishPath() != null
                ? properties.testHarness().demoPublishPath()
                : "/demo/publish-offer-event";
    }

    private int defaultFaultStatus() {
        return properties.testHarness() != null ? properties.testHarness().defaultFaultStatus() : 503;
    }
}
