package bank.internalgateway.gateway.web;

import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.identity.IdentityEnvelopeService;
import bank.internalgateway.gateway.messaging.DeliveryHeaders;
import bank.internalgateway.gateway.observability.RequestTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@RestController
public class IngressController {

    private static final Logger log = LoggerFactory.getLogger(IngressController.class);
    private static final String DEPOSIT_OFFER_SERVICE = "deposit-offer-service";
    private static final String OFFERS_SEARCH_PATH = "/internal/v1/offers/search";

    private final ServiceUrlResolver serviceUrlResolver;
    private final IdentityEnvelopeService identityEnvelopeService;
    private final RestClient restClient;
    private final RequestTraceService requestTraceService;

    public IngressController(
            ServiceUrlResolver serviceUrlResolver,
            IdentityEnvelopeService identityEnvelopeService,
            RestClient restClient,
            RequestTraceService requestTraceService) {
        this.serviceUrlResolver = serviceUrlResolver;
        this.identityEnvelopeService = identityEnvelopeService;
        this.restClient = restClient;
        this.requestTraceService = requestTraceService;
    }

    @PostMapping("/deposit-offers/search")
    public ResponseEntity<String> searchDepositOffers(
            @RequestBody JsonNode requestBody,
            @RequestHeader(value = "X-Demo-Subject-Id", defaultValue = "demo-user-001") String subjectId,
            @RequestHeader(value = "X-Demo-Organization-Id", required = false) String organizationIdHeader) {

        String organizationId = organizationIdHeader != null
                ? organizationIdHeader
                : requestBody.path("organizationId").asText("org-demo-001");
        String correlationId = UUID.randomUUID().toString();
        String inboundPath = "/deposit-offers/search";
        String targetService = DEPOSIT_OFFER_SERVICE;
        String targetUrl = serviceUrlResolver.resolve(DEPOSIT_OFFER_SERVICE) + OFFERS_SEARCH_PATH;

        long started = System.currentTimeMillis();
        var trace = requestTraceService.start("ingress", "POST", inboundPath, targetService, targetUrl);

        try {
            log.debug("Business Control stub PASSED for deposit.offers.search");

            String envelope = identityEnvelopeService.createBankUserEnvelope(subjectId, organizationId, correlationId);

            String response = restClient.post()
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
