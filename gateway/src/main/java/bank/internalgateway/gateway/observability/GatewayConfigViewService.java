package bank.internalgateway.gateway.observability;

import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.dsl.DslLoader;
import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import bank.internalgateway.gateway.messaging.EventMappingModels;
import bank.internalgateway.gateway.messaging.EventMappingRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class GatewayConfigViewService {

    private final DslLoader dslLoader;
    private final GatewayProperties properties;
    private final ServiceUrlResolver serviceUrlResolver;
    private final EventMappingRegistry eventMappingRegistry;
    private final ConsumeBindingRegistry consumeBindingRegistry;

    public GatewayConfigViewService(
            DslLoader dslLoader,
            GatewayProperties properties,
            ServiceUrlResolver serviceUrlResolver,
            EventMappingRegistry eventMappingRegistry,
            ConsumeBindingRegistry consumeBindingRegistry) {
        this.dslLoader = dslLoader;
        this.properties = properties;
        this.serviceUrlResolver = serviceUrlResolver;
        this.eventMappingRegistry = eventMappingRegistry;
        this.consumeBindingRegistry = consumeBindingRegistry;
    }

    public GatewayConfigView build() {
        return new GatewayConfigView(
                runtimeConfig(),
                ingressRoutes(),
                capabilities(),
                messagingBindings(),
                primaryEventMappingView(),
                eventMappingRegistry.registeredMappings(),
                consumeBindingRegistry.allFanOutRoutes(),
                Map.of(
                        "opening", moduleMeta(dslLoader.openingModule()),
                        "messaging", moduleMeta(dslLoader.messagingModule())
                )
        );
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dslPath", properties.dslPath());
        config.put("depositOfferServiceUrl", properties.depositOfferServiceUrl());
        config.put("corsAllowedOrigins", properties.corsAllowedOrigins());
        config.put("services", serviceUrlResolver.configuredServices());
        if (properties.kafka() != null) {
            config.put("activeKafkaBindingId", properties.kafka().activeBindingId());
        }
        return config;
    }

    private EventMappingModels.MappingConfigView primaryEventMappingView() {
        String bindingId = properties.kafka() != null ? properties.kafka().activeBindingId() : null;
        if (bindingId != null) {
            return eventMappingRegistry.configViewForBinding(bindingId);
        }
        List<EventMappingRegistry.RegisteredEventMappingView> mappings = eventMappingRegistry.registeredMappings();
        if (!mappings.isEmpty()) {
            return eventMappingRegistry.configViewForFile(mappings.getFirst().configFile());
        }
        return new EventMappingModels.MappingConfigView("", "", Map.of(), Map.of(), Map.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private List<RouteView> ingressRoutes() {
        List<RouteView> routes = new ArrayList<>();
        routes.add(new RouteView(
                "search-deposit-offers",
                "POST",
                "/deposit-offers/search",
                "deposit-offer-service",
                properties.depositOfferServiceUrl() + "/internal/v1/offers/search",
                "PoC ingress: Business Control stub + identity envelope"
        ));

        Object routesObj = dslLoader.openingModule().get("routes");
        if (routesObj instanceof List<?> dslRoutes) {
            for (Object item : dslRoutes) {
                if (!(item instanceof Map<?, ?> route)) {
                    continue;
                }
                String id = stringValue(route.get("id"));
                Map<String, Object> request = mapValue(route.get("request"));
                Map<String, Object> target = mapValue(route.get("target"));
                if (request == null) {
                    continue;
                }
                String path = stringValue(request.get("path"));
                String method = stringValue(request.get("method"));
                if (path == null || method == null) {
                    continue;
                }
                if (routes.stream().anyMatch(r -> r.inboundPath().equals(path) && r.method().equals(method))) {
                    continue;
                }
                routes.add(new RouteView(
                        id,
                        method,
                        path,
                        stringValue(target != null ? target.get("service") : null),
                        target != null ? stringValue(target.get("path")) : null,
                        "Declared in deposit-opening-gateway.dsl.yaml"
                ));
            }
        }
        return routes;
    }

    @SuppressWarnings("unchecked")
    private List<CapabilityView> capabilities() {
        List<CapabilityView> result = new ArrayList<>();
        result.add(new CapabilityView(
                "account-deposit-context",
                "GET",
                "/internal/capabilities/accounts/{accountId}/deposit-context",
                "account-context-provider (stub)",
                "PoC stub response"
        ));
        result.add(new CapabilityView(
                "organization-display-info",
                "GET",
                "/internal/capabilities/organizations/{organizationId}/display-info",
                "organization-directory-provider (stub)",
                "PoC stub response"
        ));

        Object capabilitiesObj = dslLoader.openingModule().get("capabilities");
        if (capabilitiesObj instanceof List<?> capabilities) {
            for (Object item : capabilities) {
                if (!(item instanceof Map<?, ?> capability)) {
                    continue;
                }
                String id = stringValue(capability.get("id"));
                Map<String, Object> request = mapValue(capability.get("request"));
                if (request == null) {
                    continue;
                }
                String path = stringValue(request.get("path"));
                String method = stringValue(request.get("method"));
                if (path == null || result.stream().anyMatch(c -> c.path().equals(path))) {
                    continue;
                }
                result.add(new CapabilityView(
                        id,
                        method,
                        path,
                        stringValue(capability.get("providerSet")),
                        "Declared in deposit-opening-gateway.dsl.yaml"
                ));
            }
        }
        return result;
    }

    private List<MessagingBindingView> messagingBindings() {
        List<MessagingBindingView> bindings = new ArrayList<>();

        for (ConsumeBindingRegistry.ConsumeBinding binding : consumeBindingRegistry.allBindings()) {
            List<String> targets = binding.fanOutTargets().stream()
                    .flatMap(target -> {
                        if (!target.pathByEventType().isEmpty()) {
                            return target.pathByEventType().values().stream();
                        }
                        return target.defaultPath() != null ? Stream.of(target.defaultPath()) : Stream.empty();
                    })
                    .distinct()
                    .toList();

            bindings.add(new MessagingBindingView(
                    binding.bindingId(),
                    "consume",
                    binding.topicAlias(),
                    binding.physicalTopic(),
                    binding.consumerGroup(),
                    binding.mappingFile(),
                    targets
            ));
        }

        Object messaging = dslLoader.messagingModule().get("messaging");
        if (messaging instanceof Map<?, ?> messagingMap) {
            Object publishRoutesObj = messagingMap.get("publishRoutes");
            if (publishRoutesObj instanceof List<?> publishRoutes) {
                for (Object item : publishRoutes) {
                    if (!(item instanceof Map<?, ?> route)) {
                        continue;
                    }
                    Map<String, Object> mapping = mapValue(route.get("mapping"));
                    Map<String, Object> request = mapValue(route.get("request"));
                    String publishPath = request != null ? stringValue(request.get("path")) : null;
                    bindings.add(new MessagingBindingView(
                            stringValue(route.get("id")),
                            "publish",
                            mapping != null ? stringValue(mapping.get("topicAlias")) : null,
                            null,
                            null,
                            null,
                            publishPath != null ? List.of(publishPath) : List.of()
                    ));
                }
            }
        }
        return bindings;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> moduleMeta(Map<String, Object> module) {
        Object metadata = module.get("metadata");
        if (!(metadata instanceof Map<?, ?> meta)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", meta.get("name"));
        result.put("version", meta.get("version"));
        result.put("owner", meta.get("owner"));
        result.put("system", meta.get("system"));
        result.put("description", meta.get("description"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    public record GatewayConfigView(
            Map<String, Object> runtime,
            List<RouteView> ingressRoutes,
            List<CapabilityView> capabilities,
            List<MessagingBindingView> messagingBindings,
            EventMappingModels.MappingConfigView eventMapping,
            List<EventMappingRegistry.RegisteredEventMappingView> eventMappings,
            List<ConsumeBindingRegistry.ResolvedFanOutRouteView> fanOutRoutes,
            Map<String, Object> modules
    ) {
    }

    public record RouteView(
            String routeId,
            String method,
            String inboundPath,
            String targetService,
            String targetPath,
            String note
    ) {
    }

    public record CapabilityView(
            String capabilityId,
            String method,
            String path,
            String providerSet,
            String note
    ) {
    }

    public record MessagingBindingView(
            String bindingId,
            String direction,
            String topicAlias,
            String physicalTopic,
            String consumerGroup,
            String mappingFile,
            List<String> targets
    ) {
    }
}
