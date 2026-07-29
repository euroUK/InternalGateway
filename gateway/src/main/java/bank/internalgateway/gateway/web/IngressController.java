package bank.internalgateway.gateway.web;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.dsl.EnrichedResponseMapper;
import bank.internalgateway.dsl.PathTemplate;
import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.identity.IdentityEnvelopeService;
import bank.internalgateway.gateway.messaging.DeliveryHeaders;
import bank.internalgateway.gateway.observability.RequestTraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

@RestController
public class IngressController {

    private static final Logger log = LoggerFactory.getLogger(IngressController.class);
    private static final String SEARCH_PATH = "/deposit-offers/search";
    private static final String ENRICHED_PATH = "/deposit-offers/enriched";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BenchmarkRouteRegistry benchmarkRouteRegistry;
    private final ServiceUrlResolver serviceUrlResolver;
    private final IdentityEnvelopeService identityEnvelopeService;
    private final RestClient restClient;
    private final RequestTraceService requestTraceService;
    private final ObjectMapper objectMapper;
    private final String selfBaseUrl;

    public IngressController(
            BenchmarkRouteRegistry benchmarkRouteRegistry,
            ServiceUrlResolver serviceUrlResolver,
            IdentityEnvelopeService identityEnvelopeService,
            RestClient restClient,
            RequestTraceService requestTraceService,
            ObjectMapper objectMapper,
            GatewayProperties gatewayProperties) {
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
        this.serviceUrlResolver = serviceUrlResolver;
        this.identityEnvelopeService = identityEnvelopeService;
        this.restClient = restClient;
        this.requestTraceService = requestTraceService;
        this.objectMapper = objectMapper;
        String configured = gatewayProperties.selfBaseUrl();
        this.selfBaseUrl = configured != null && !configured.isBlank()
                ? configured.replaceAll("/$", "")
                : "http://127.0.0.1:8080";
    }

    @PostMapping("/deposit-offers/search")
    public ResponseEntity<String> searchDepositOffers(
            @RequestBody JsonNode requestBody,
            @RequestHeader(value = "X-Demo-Subject-Id", defaultValue = "demo-user-001") String subjectId,
            @RequestHeader(value = "X-Demo-Organization-Id", required = false) String organizationIdHeader) {

        CompiledBenchmarkModule.CompiledIngressRoute route =
                benchmarkRouteRegistry.currentModule().requireIngressByPath("POST", SEARCH_PATH);

        String organizationId = resolveOrganizationId(organizationIdHeader, requestBody);
        String correlationId = UUID.randomUUID().toString();
        String targetService = route.targetService();
        String targetUrl = serviceUrlResolver.resolve(targetService) + route.targetPath();

        long started = System.currentTimeMillis();
        var trace = requestTraceService.start("ingress", route.method(), route.inboundPath(), targetService, targetUrl);

        try {
            if (route.businessControlStub()) {
                log.debug("Business Control stub PASSED for {}", route.routeId());
            }

            String envelope = identityEnvelopeService.createBankUserEnvelope(
                    subjectId,
                    organizationId,
                    correlationId,
                    route.businessControlEvidenceId());

            String response = restClient.method(HttpMethod.valueOf(route.targetMethod()))
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DeliveryHeaders.IDENTITY_ENVELOPE, envelope)
                    .header(DeliveryHeaders.CORRELATION_ID, correlationId)
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    "200",
                    System.currentTimeMillis() - started,
                    "subjectId=" + subjectId + ", organizationId=" + organizationId
            );

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(DeliveryHeaders.CORRELATION_ID, correlationId)
                    .body(response);
        } catch (RestClientResponseException ex) {
            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    String.valueOf(ex.getStatusCode().value()),
                    System.currentTimeMillis() - started,
                    ex.getResponseBodyAsString()
            );
            throw ex;
        } catch (RuntimeException ex) {
            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    "ERROR",
                    System.currentTimeMillis() - started,
                    ex.getMessage()
            );
            throw ex;
        }
    }

    @PostMapping("/deposit-offers/enriched")
    public ResponseEntity<String> enrichedDepositOffers(
            @RequestBody JsonNode requestBody,
            @RequestHeader(value = "X-Demo-Subject-Id", defaultValue = "demo-user-001") String subjectId,
            @RequestHeader(value = "X-Demo-Organization-Id", required = false) String organizationIdHeader)
            throws Exception {

        CompiledBenchmarkModule.CompiledIngressRoute route =
                benchmarkRouteRegistry.currentModule().requireIngressByPath("POST", ENRICHED_PATH);
        if (!route.isEnriched()) {
            throw new IllegalStateException("Route " + route.routeId() + " is not enriched");
        }

        String organizationId = resolveOrganizationId(organizationIdHeader, requestBody);
        String correlationId = UUID.randomUUID().toString();
        String targetService = route.targetService();
        String targetUrl = serviceUrlResolver.resolve(targetService) + route.targetPath();
        String adapterPath = PathTemplate.expand(
                route.adapter().pathTemplate(),
                Map.of("organizationId", organizationId));
        String adapterUrl = selfBaseUrl + adapterPath;

        long started = System.currentTimeMillis();
        var trace = requestTraceService.start(
                "ingress",
                route.method(),
                route.inboundPath(),
                targetService,
                targetUrl);

        try {
            if (route.businessControlStub()) {
                log.debug("Business Control stub PASSED for {}", route.routeId());
            }

            String adapterBody = restClient.method(HttpMethod.valueOf(route.adapter().method()))
                    .uri(adapterUrl)
                    .header(DeliveryHeaders.CORRELATION_ID, correlationId)
                    .retrieve()
                    .body(String.class);
            JsonNode adapterJson = objectMapper.readTree(adapterBody != null ? adapterBody : "{}");
            String displayName = adapterJson.path("displayName").asText(null);

            String envelope = identityEnvelopeService.createBankUserEnvelope(
                    subjectId,
                    organizationId,
                    correlationId,
                    route.businessControlEvidenceId());

            ObjectNode targetRequest = requestBody.deepCopy();
            if (displayName != null && !displayName.isBlank()) {
                targetRequest.put("organizationDisplayName", displayName);
            }

            String targetBody = restClient.method(HttpMethod.valueOf(route.targetMethod()))
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DeliveryHeaders.IDENTITY_ENVELOPE, envelope)
                    .header(DeliveryHeaders.CORRELATION_ID, correlationId)
                    .body(targetRequest.toString())
                    .retrieve()
                    .body(String.class);

            Map<String, Object> requestMap = objectMapper.convertValue(requestBody, MAP_TYPE);
            Map<String, Object> adapterMap = objectMapper.convertValue(adapterJson, MAP_TYPE);
            Map<String, Object> targetMap = objectMapper.readValue(
                    targetBody != null ? targetBody : "{}",
                    MAP_TYPE);
            Map<String, Object> mapped = EnrichedResponseMapper.map(
                    route.responseMapping(),
                    requestMap,
                    adapterMap,
                    targetMap,
                    correlationId);

            String responseJson = objectMapper.writeValueAsString(mapped);
            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    "200",
                    System.currentTimeMillis() - started,
                    "adapter=" + adapterPath + ", target=" + route.targetPath()
                            + ", subjectId=" + subjectId + ", organizationId=" + organizationId
            );

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(DeliveryHeaders.CORRELATION_ID, correlationId)
                    .body(responseJson);
        } catch (RestClientResponseException ex) {
            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    String.valueOf(ex.getStatusCode().value()),
                    System.currentTimeMillis() - started,
                    "adapter=" + adapterPath + ", " + ex.getResponseBodyAsString()
            );
            throw ex;
        } catch (RuntimeException ex) {
            requestTraceService.complete(
                    withCorrelation(trace, correlationId),
                    "ERROR",
                    System.currentTimeMillis() - started,
                    "adapter=" + adapterPath + ", " + ex.getMessage()
            );
            throw ex;
        }
    }

    private static String resolveOrganizationId(String organizationIdHeader, JsonNode requestBody) {
        if (organizationIdHeader != null && !organizationIdHeader.isBlank()) {
            return organizationIdHeader;
        }
        return requestBody.path("organizationId").asText("org-demo-001");
    }

    private RequestTraceService.RequestTraceEntry withCorrelation(
            RequestTraceService.RequestTraceEntry trace,
            String correlationId) {
        return new RequestTraceService.RequestTraceEntry(
                trace.id(),
                trace.timestamp(),
                trace.plane(),
                trace.method(),
                trace.inboundPath(),
                trace.targetService(),
                trace.targetUrl(),
                correlationId,
                trace.status(),
                trace.durationMs(),
                trace.detail()
        );
    }
}
