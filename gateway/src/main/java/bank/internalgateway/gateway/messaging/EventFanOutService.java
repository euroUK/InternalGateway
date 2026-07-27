package bank.internalgateway.gateway.messaging;

import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.identity.IdentityEnvelopeService;
import bank.internalgateway.gateway.observability.RequestTraceService;
import bank.internalgateway.gateway.resilience.EventDedupCache;
import bank.internalgateway.gateway.resilience.GatewayRateLimiter;
import bank.internalgateway.gateway.resilience.ResilienceDeliveryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventFanOutService {

    private static final Logger log = LoggerFactory.getLogger(EventFanOutService.class);

    private final ConsumeBindingRegistry bindingRegistry;
    private final ServiceUrlResolver serviceUrlResolver;
    private final IdentityEnvelopeService identityEnvelopeService;
    private final ObjectMapper objectMapper;
    private final RequestTraceService requestTraceService;
    private final EventDedupCache dedupCache;
    private final GatewayRateLimiter rateLimiter;
    private final ResilienceDeliveryExecutor deliveryExecutor;

    public EventFanOutService(
            ConsumeBindingRegistry bindingRegistry,
            ServiceUrlResolver serviceUrlResolver,
            IdentityEnvelopeService identityEnvelopeService,
            ObjectMapper objectMapper,
            RequestTraceService requestTraceService,
            EventDedupCache dedupCache,
            GatewayRateLimiter rateLimiter,
            ResilienceDeliveryExecutor deliveryExecutor) {
        this.bindingRegistry = bindingRegistry;
        this.serviceUrlResolver = serviceUrlResolver;
        this.identityEnvelopeService = identityEnvelopeService;
        this.objectMapper = objectMapper;
        this.requestTraceService = requestTraceService;
        this.dedupCache = dedupCache;
        this.rateLimiter = rateLimiter;
        this.deliveryExecutor = deliveryExecutor;
    }

    public void deliver(String bindingId, CanonicalInboundEvent event) throws Exception {
        ConsumeBindingRegistry.ConsumeBinding binding = bindingRegistry.findById(bindingId)
                .orElseThrow(() -> new IllegalStateException("Unknown consume binding: " + bindingId));

        String inboundPath = binding.inboundKafkaPath();
        String dedupField = ConsumeBindingRegistry.dedupFieldFromKey(binding.dedupKey());
        String dedupValue = stringClaimValue(event, dedupField);
        if (dedupCache.isDuplicate(bindingId, binding.dedupTtl(), dedupValue)) {
            log.info("Skipping duplicate event binding={} {}={}", bindingId, dedupField, dedupValue);
            requestTraceService.recordSimple(
                    "messaging",
                    "KAFKA",
                    inboundPath,
                    binding.fanOutTargets().isEmpty() ? "—" : binding.fanOutTargets().getFirst().service(),
                    "(dedup skipped)",
                    event.eventId(),
                    "DEDUP",
                    0,
                    event.mappingSummary()
            );
            return;
        }

        List<ConsumeBindingRegistry.FanOutRoute> routes = bindingRegistry.resolveRoutes(bindingId, event.eventType());
        if (routes.isEmpty()) {
            throw new IllegalStateException(
                    "No fan-out route for binding '" + bindingId + "' and eventType '" + event.eventType() + "'");
        }

        String payloadJson = objectMapper.writeValueAsString(event.payload());
        Exception lastError = null;

        for (ConsumeBindingRegistry.FanOutRoute route : routes) {
            try {
                deliverToRoute(binding, route, event, payloadJson, inboundPath);
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }

    private void deliverToRoute(
            ConsumeBindingRegistry.ConsumeBinding binding,
            ConsumeBindingRegistry.FanOutRoute route,
            CanonicalInboundEvent event,
            String payloadJson,
            String inboundPath) {

        String rateScope = binding.bindingId() + ":" + route.targetId();
        if (route.rateLimitProfile() != null && !rateLimiter.tryAcquire(route.rateLimitProfile(), rateScope)) {
            log.warn("Rate limited binding={} target={} profile={}", binding.bindingId(), route.targetId(), route.rateLimitProfile());
            requestTraceService.recordSimple(
                    "messaging",
                    route.method(),
                    inboundPath + " [" + event.sourceMessageType() + "→" + event.eventType() + "]",
                    route.service(),
                    "(rate limited)",
                    event.eventId(),
                    "RATE_LIMIT",
                    0,
                    "profile=" + route.rateLimitProfile()
            );
            return;
        }

        String targetUrl = serviceUrlResolver.resolve(route.service()) + route.path();
        Map<String, Object> envelopeClaims = buildEnvelopeClaims(route.envelopeClaims(), event);
        String envelope = identityEnvelopeService.createDeliveryEnvelope(envelopeClaims);

        long started = System.currentTimeMillis();
        log.info("Fan-out binding={} target={} {} eventId={} -> {} ({})",
                binding.bindingId(),
                route.targetId(),
                event.eventType(),
                event.eventId(),
                route.path(),
                event.mappingSummary());

        String traceInbound = inboundPath + " [" + event.sourceMessageType() + "→" + event.eventType() + "]";
        String resilienceProfile = route.resilienceProfile() != null
                ? route.resilienceProfile()
                : "internalEventDelivery";

        ResilienceDeliveryExecutor.DeliveryResult result = deliveryExecutor.deliver(
                resilienceProfile,
                route.method(),
                targetUrl,
                payloadJson,
                request -> request
                        .header("X-Delivery-Envelope", envelope)
                        .header("X-Event-Id", event.eventId())
                        .header("X-Event-Type", event.eventType())
        );

        String detail = result.attempts() > 1
                ? event.mappingSummary() + " · retries=" + (result.attempts() - 1)
                : event.mappingSummary();

        if (result.success()) {
            requestTraceService.recordSimple(
                    "messaging",
                    route.method(),
                    traceInbound,
                    route.service(),
                    targetUrl,
                    event.eventId(),
                    "202",
                    System.currentTimeMillis() - started,
                    detail
            );
            return;
        }

        requestTraceService.recordSimple(
                "messaging",
                route.method(),
                traceInbound,
                route.service(),
                targetUrl,
                event.eventId(),
                result.statusCode() != null ? String.valueOf(result.statusCode()) : "ERROR",
                System.currentTimeMillis() - started,
                result.detail()
        );
        if (result.statusCode() != null) {
            throw new IllegalStateException(
                    "Delivery failed with HTTP " + result.statusCode() + " after " + result.attempts() + " attempt(s): "
                            + result.detail());
        }
        throw new IllegalStateException(
                "Delivery failed after " + result.attempts() + " attempt(s): " + result.detail());
    }

    private Map<String, Object> buildEnvelopeClaims(List<String> claimNames, CanonicalInboundEvent event) {
        Map<String, Object> claims = new LinkedHashMap<>();
        for (String claimName : claimNames) {
            Object value = resolveClaimValue(event, claimName);
            if (value != null) {
                claims.put(claimName, value);
            }
        }
        return claims;
    }

    private Object resolveClaimValue(CanonicalInboundEvent event, String claimName) {
        return switch (claimName) {
            case "eventId" -> event.eventId();
            case "eventType" -> event.eventType();
            case "occurredAt" -> event.occurredAt();
            default -> event.payload().get(claimName);
        };
    }

    private String stringClaimValue(CanonicalInboundEvent event, String claimName) {
        Object value = resolveClaimValue(event, claimName);
        return value != null ? String.valueOf(value) : null;
    }
}
