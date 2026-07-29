package bank.internalgateway.scg.filter;

import bank.internalgateway.dsl.EnrichedResponseMapper;
import bank.internalgateway.dsl.PathTemplate;
import bank.internalgateway.scg.config.ScgGatewayProperties;
import bank.internalgateway.scg.identity.IdentityEnvelopeService;
import bank.internalgateway.scg.observability.RequestTraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enriched ingress: loopback org capability → JWT envelope → fixed deposit → response map.
 * Completes the exchange without proxying through the default SCG chain.
 */
@Component
public class EnrichedDepositGatewayFilterFactory
        extends AbstractGatewayFilterFactory<EnrichedDepositGatewayFilterFactory.Config> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final IdentityEnvelopeService identityEnvelopeService;
    private final RequestTraceService requestTraceService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String targetBaseUrl;
    private final String selfBaseUrl;

    public EnrichedDepositGatewayFilterFactory(
            IdentityEnvelopeService identityEnvelopeService,
            RequestTraceService requestTraceService,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            ScgGatewayProperties properties) {
        super(Config.class);
        this.identityEnvelopeService = identityEnvelopeService;
        this.requestTraceService = requestTraceService;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
        this.targetBaseUrl = trimTrailingSlash(properties.depositOfferServiceUrl());
        String self = properties.selfBaseUrl();
        this.selfBaseUrl = trimTrailingSlash(
                self != null && !self.isBlank() ? self : "http://127.0.0.1:8081");
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of(
                "benchmarkRouteId",
                "inboundPath",
                "targetService",
                "targetPath",
                "targetMethod",
                "businessControlEvidenceId",
                "adapterMethod",
                "adapterPathTemplate",
                "responseMappingJson"
        );
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, request -> {
            DataBuffer bodyBuffer = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);
            byte[] bodyBytes = toBytes(bodyBuffer);
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            long started = System.currentTimeMillis();
            String correlationId = UUID.randomUUID().toString();
            exchange.getResponse().getHeaders().set(
                    BenchmarkIngressGatewayFilterFactory.CORRELATION_ID, correlationId);

            String inboundPath = firstNonBlank(config.getInboundPath(), request.getPath().value());
            String targetPath = firstNonBlank(config.getTargetPath(), "/internal/v1/offers/fixed");
            String targetService = firstNonBlank(config.getTargetService(), "deposit-offer-service");
            String targetUrl = targetBaseUrl + targetPath;
            String evidenceId = firstNonBlank(config.getBusinessControlEvidenceId(), "poc-stub-passed");
            String adapterMethod = firstNonBlank(config.getAdapterMethod(), "GET");
            String adapterTemplate = firstNonBlank(
                    config.getAdapterPathTemplate(),
                    "/internal/capabilities/organizations/{organizationId}/display-info");

            String subjectId = firstHeader(
                    exchange, BenchmarkIngressGatewayFilterFactory.DEMO_SUBJECT_ID, "demo-user-001");
            String organizationId = resolveOrganizationId(exchange, body);
            String adapterPath;
            try {
                adapterPath = PathTemplate.expand(adapterTemplate, Map.of("organizationId", organizationId));
            } catch (RuntimeException ex) {
                return writeError(exchange, HttpStatus.BAD_REQUEST, ex.getMessage());
            }
            String adapterUrl = selfBaseUrl + adapterPath;
            Map<String, String> responseMapping = parseResponseMapping(config.getResponseMappingJson());

            return webClient.method(HttpMethod.valueOf(adapterMethod))
                    .uri(adapterUrl)
                    .header(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID, correlationId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .defaultIfEmpty("{}")
                    .flatMap(adapterBody -> {
                        try {
                            JsonNode adapterJson = objectMapper.readTree(adapterBody);
                            String displayName = adapterJson.path("displayName").asText(null);
                            String envelope = identityEnvelopeService.createBankUserEnvelope(
                                    subjectId, organizationId, correlationId, evidenceId);

                            JsonNode requestJson = body.isBlank()
                                    ? objectMapper.createObjectNode()
                                    : objectMapper.readTree(body);
                            ObjectNode targetRequest = requestJson.deepCopy();
                            if (displayName != null && !displayName.isBlank()) {
                                targetRequest.put("organizationDisplayName", displayName);
                            }

                            return webClient.method(HttpMethod.valueOf(
                                            firstNonBlank(config.getTargetMethod(), "POST")))
                                    .uri(targetUrl)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header(BenchmarkIngressGatewayFilterFactory.IDENTITY_ENVELOPE, envelope)
                                    .header(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID, correlationId)
                                    .bodyValue(targetRequest.toString())
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .defaultIfEmpty("{}")
                                    .flatMap(targetBody -> {
                                        try {
                                            Map<String, Object> requestMap =
                                                    objectMapper.convertValue(requestJson, MAP_TYPE);
                                            Map<String, Object> adapterMap =
                                                    objectMapper.convertValue(adapterJson, MAP_TYPE);
                                            Map<String, Object> targetMap =
                                                    objectMapper.readValue(targetBody, MAP_TYPE);
                                            Map<String, Object> mapped = EnrichedResponseMapper.map(
                                                    responseMapping,
                                                    requestMap,
                                                    adapterMap,
                                                    targetMap,
                                                    correlationId);
                                            String responseJson = objectMapper.writeValueAsString(mapped);
                                            requestTraceService.recordSimple(
                                                    "ingress",
                                                    "POST",
                                                    inboundPath,
                                                    targetService,
                                                    targetUrl,
                                                    correlationId,
                                                    "200",
                                                    System.currentTimeMillis() - started,
                                                    "adapter=" + adapterPath + ", target=" + targetPath
                                                            + ", subjectId=" + subjectId
                                                            + ", organizationId=" + organizationId
                                            );
                                            return writeJson(exchange, HttpStatus.OK, responseJson);
                                        } catch (Exception ex) {
                                            requestTraceService.recordSimple(
                                                    "ingress",
                                                    "POST",
                                                    inboundPath,
                                                    targetService,
                                                    targetUrl,
                                                    correlationId,
                                                    "ERROR",
                                                    System.currentTimeMillis() - started,
                                                    ex.getMessage());
                                            return writeError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
                                        }
                                    });
                        } catch (Exception ex) {
                            requestTraceService.recordSimple(
                                    "ingress",
                                    "POST",
                                    inboundPath,
                                    targetService,
                                    targetUrl,
                                    correlationId,
                                    "ERROR",
                                    System.currentTimeMillis() - started,
                                    "adapter=" + adapterPath + ", " + ex.getMessage());
                            return writeError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
                        }
                    })
                    .onErrorResume(ex -> {
                        requestTraceService.recordSimple(
                                "ingress",
                                "POST",
                                inboundPath,
                                targetService,
                                targetUrl,
                                correlationId,
                                "ERROR",
                                System.currentTimeMillis() - started,
                                "adapter=" + adapterPath + ", " + ex.getMessage());
                        return writeError(exchange, HttpStatus.BAD_GATEWAY, ex.getMessage());
                    });
        });
    }

    public static class Config {
        private String benchmarkRouteId;
        private String inboundPath;
        private String targetService;
        private String targetPath;
        private String targetMethod;
        private String businessControlEvidenceId;
        private String adapterMethod;
        private String adapterPathTemplate;
        private String responseMappingJson;

        public String getBenchmarkRouteId() {
            return benchmarkRouteId;
        }

        public void setBenchmarkRouteId(String benchmarkRouteId) {
            this.benchmarkRouteId = benchmarkRouteId;
        }

        public String getInboundPath() {
            return inboundPath;
        }

        public void setInboundPath(String inboundPath) {
            this.inboundPath = inboundPath;
        }

        public String getTargetService() {
            return targetService;
        }

        public void setTargetService(String targetService) {
            this.targetService = targetService;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public void setTargetPath(String targetPath) {
            this.targetPath = targetPath;
        }

        public String getTargetMethod() {
            return targetMethod;
        }

        public void setTargetMethod(String targetMethod) {
            this.targetMethod = targetMethod;
        }

        public String getBusinessControlEvidenceId() {
            return businessControlEvidenceId;
        }

        public void setBusinessControlEvidenceId(String businessControlEvidenceId) {
            this.businessControlEvidenceId = businessControlEvidenceId;
        }

        public String getAdapterMethod() {
            return adapterMethod;
        }

        public void setAdapterMethod(String adapterMethod) {
            this.adapterMethod = adapterMethod;
        }

        public String getAdapterPathTemplate() {
            return adapterPathTemplate;
        }

        public void setAdapterPathTemplate(String adapterPathTemplate) {
            this.adapterPathTemplate = adapterPathTemplate;
        }

        public String getResponseMappingJson() {
            return responseMappingJson;
        }

        public void setResponseMappingJson(String responseMappingJson) {
            this.responseMappingJson = responseMappingJson;
        }
    }

    private Map<String, String> parseResponseMapping(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return parsed != null ? parsed : Map.of();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid responseMappingJson", ex);
        }
    }

    private String resolveOrganizationId(ServerWebExchange exchange, String body) {
        String fromHeader = firstHeader(exchange, BenchmarkIngressGatewayFilterFactory.DEMO_ORGANIZATION_ID, null);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        try {
            if (body != null && !body.isBlank()) {
                JsonNode node = objectMapper.readTree(body);
                JsonNode organization = node.get("organizationId");
                if (organization != null && !organization.isNull() && !organization.asText().isBlank()) {
                    return organization.asText();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "org-demo-001";
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        String safe = message != null ? message : status.getReasonPhrase();
        return writeJson(exchange, status, "{\"error\":\"" + safe.replace("\"", "'") + "\"}");
    }

    private static byte[] toBytes(DataBuffer bodyBuffer) {
        if (bodyBuffer == null) {
            return new byte[0];
        }
        byte[] bytes = new byte[bodyBuffer.readableByteCount()];
        bodyBuffer.read(bytes);
        bodyBuffer.readPosition(0);
        return bytes;
    }

    private static String firstHeader(ServerWebExchange exchange, String name, String defaultValue) {
        List<String> values = exchange.getRequest().getHeaders().get(name);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            return defaultValue;
        }
        return values.getFirst();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String trimTrailingSlash(String value) {
        return value != null ? value.replaceAll("/$", "") : "";
    }
}
