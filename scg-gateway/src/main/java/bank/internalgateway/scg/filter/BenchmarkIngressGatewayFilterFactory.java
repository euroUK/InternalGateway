package bank.internalgateway.scg.filter;

import bank.internalgateway.scg.config.ScgGatewayProperties;
import bank.internalgateway.scg.identity.IdentityEnvelopeService;
import bank.internalgateway.scg.observability.RequestTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * DSL-driven ingress filter: JWT envelope, correlation id, path rewrite, tracing.
 * Route metadata comes from compiled benchmark DSL (via filter args).
 */
@Component
public class BenchmarkIngressGatewayFilterFactory
        extends AbstractGatewayFilterFactory<BenchmarkIngressGatewayFilterFactory.Config> {

    public static final String IDENTITY_ENVELOPE = "X-Identity-Envelope";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String DEMO_SUBJECT_ID = "X-Demo-Subject-Id";
    public static final String DEMO_ORGANIZATION_ID = "X-Demo-Organization-Id";

    private final IdentityEnvelopeService identityEnvelopeService;
    private final RequestTraceService requestTraceService;
    private final ObjectMapper objectMapper;
    private final String targetBaseUrl;

    public BenchmarkIngressGatewayFilterFactory(
            IdentityEnvelopeService identityEnvelopeService,
            RequestTraceService requestTraceService,
            ObjectMapper objectMapper,
            ScgGatewayProperties properties) {
        super(Config.class);
        this.identityEnvelopeService = identityEnvelopeService;
        this.requestTraceService = requestTraceService;
        this.objectMapper = objectMapper;
        this.targetBaseUrl = properties.depositOfferServiceUrl();
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of(
                "benchmarkRouteId",
                "inboundPath",
                "targetService",
                "targetPath",
                "targetMethod",
                "businessControlEvidenceId"
        );
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            HttpMethod expected = HttpMethod.valueOf(
                    firstNonBlank(config.getTargetMethod(), "POST"));
            if (exchange.getRequest().getMethod() != expected) {
                return chain.filter(exchange);
            }

            String inboundPath = firstNonBlank(
                    config.getInboundPath(), exchange.getRequest().getPath().value());
            String targetPath = firstNonBlank(config.getTargetPath(), "/internal/v1/offers/search");
            String targetService = firstNonBlank(config.getTargetService(), "deposit-offer-service");
            String evidenceId = firstNonBlank(config.getBusinessControlEvidenceId(), "poc-stub-passed");
            long started = System.currentTimeMillis();
            String targetUrl = targetBaseUrl + targetPath;

            return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, serverHttpRequest -> {
                DataBuffer bodyBuffer = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);
                byte[] bodyBytes = toBytes(bodyBuffer);
                String body = new String(bodyBytes, StandardCharsets.UTF_8);

                String subjectId = firstHeader(exchange, DEMO_SUBJECT_ID, "demo-user-001");
                String organizationId = resolveOrganizationId(exchange, body);
                String correlationId = UUID.randomUUID().toString();
                String envelope = identityEnvelopeService.createBankUserEnvelope(
                        subjectId, organizationId, correlationId, evidenceId);

                exchange.getResponse().getHeaders().set(CORRELATION_ID, correlationId);

                ServerHttpRequest mutatedRequest = serverHttpRequest.mutate()
                        .path(targetPath)
                        .headers(headers -> {
                            headers.set(IDENTITY_ENVELOPE, envelope);
                            headers.set(CORRELATION_ID, correlationId);
                            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                                headers.setContentType(MediaType.APPLICATION_JSON);
                            }
                            headers.setContentLength(bodyBytes.length);
                        })
                        .build();

                ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(mutatedRequest) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        if (bodyBytes.length == 0) {
                            return Flux.empty();
                        }
                        return Flux.defer(() -> Flux.just(
                                exchange.getResponse().bufferFactory().wrap(bodyBytes)));
                    }
                };

                ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(decoratedRequest)
                        .build();

                return chain.filter(mutatedExchange)
                        .doFinally(signal -> {
                            HttpStatusCode status = mutatedExchange.getResponse().getStatusCode();
                            String statusValue = status != null ? String.valueOf(status.value()) : "UNKNOWN";
                            if ("ON_ERROR".equals(signal.name())
                                    && (status == null || !status.isError())) {
                                statusValue = "ERROR";
                            }
                            requestTraceService.recordSimple(
                                    "ingress",
                                    expected.name(),
                                    inboundPath,
                                    targetService,
                                    targetUrl,
                                    correlationId,
                                    statusValue,
                                    System.currentTimeMillis() - started,
                                    "subjectId=" + subjectId + ", organizationId=" + organizationId
                            );
                        });
            });
        };
    }

    public static class Config {
        private String benchmarkRouteId;
        private String inboundPath;
        private String targetService;
        private String targetPath;
        private String targetMethod;
        private String businessControlEvidenceId;

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

    private String resolveOrganizationId(ServerWebExchange exchange, String body) {
        String fromHeader = firstHeader(exchange, DEMO_ORGANIZATION_ID, null);
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
            // fall through to default
        }
        return "org-demo-001";
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
}
