package bank.internalgateway.gateway.observability;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.dsl.DslLoader;
import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import bank.internalgateway.gateway.messaging.EventMappingModels;
import bank.internalgateway.gateway.messaging.EventMappingRegistry;
import bank.internalgateway.gateway.dsl.DslMaps;
import bank.internalgateway.gateway.resilience.ResilienceProfileRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class GatewayConfigViewService {

    private final DslLoader dslLoader;
    private final BenchmarkRouteRegistry benchmarkRouteRegistry;
    private final GatewayProperties properties;
    private final ServiceUrlResolver serviceUrlResolver;
    private final EventMappingRegistry eventMappingRegistry;
    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final ResilienceProfileRegistry resilienceProfileRegistry;

    public GatewayConfigViewService(
            DslLoader dslLoader,
            BenchmarkRouteRegistry benchmarkRouteRegistry,
            GatewayProperties properties,
            ServiceUrlResolver serviceUrlResolver,
            EventMappingRegistry eventMappingRegistry,
            ConsumeBindingRegistry consumeBindingRegistry,
            ResilienceProfileRegistry resilienceProfileRegistry) {
        this.dslLoader = dslLoader;
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
        this.properties = properties;
        this.serviceUrlResolver = serviceUrlResolver;
        this.eventMappingRegistry = eventMappingRegistry;
        this.consumeBindingRegistry = consumeBindingRegistry;
        this.resilienceProfileRegistry = resilienceProfileRegistry;
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
                        "messaging", moduleMeta(dslLoader.messagingModule()),
                        "offers", moduleMeta(dslLoader.offersModule())
                )
        );
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dslPath", properties.dslPath());
        config.put("corsAllowedOrigins", properties.corsAllowedOrigins());
        config.put("services", serviceUrlResolver.configuredServices());
        if (properties.kafka() != null) {
            config.put("kafkaListenerBindings", properties.kafka().listenerBindings());
        }
        config.put("resilienceProfiles", resilienceProfileRegistry.allProfiles().values().stream()
                .map(profile -> Map.of(
                        "name", profile.name(),
                        "maxAttempts", profile.maxAttempts(),
                        "timeout", profile.timeout() != null ? profile.timeout().toString() : null,
                        "backoff", profile.backoff(),
                        "circuitBreaker", profile.circuitBreaker(),
                        "deadLetter", profile.deadLetter()
                ))
                .toList());
        config.put("rateLimitProfiles", resilienceProfileRegistry.allRateLimits());
        return config;
    }

    private EventMappingModels.MappingConfigView primaryEventMappingView() {
        String bindingId = primaryListenerBindingId();
        if (bindingId != null) {
            return eventMappingRegistry.configViewForBinding(bindingId);
        }
        List<EventMappingRegistry.RegisteredEventMappingView> mappings = eventMappingRegistry.registeredMappings();
        if (!mappings.isEmpty()) {
            return eventMappingRegistry.configViewForFile(mappings.getFirst().configFile());
        }
        return new EventMappingModels.MappingConfigView("", "", Map.of(), Map.of(), Map.of(), List.of());
    }

    private List<RouteView> ingressRoutes() {
        List<RouteView> routes = new ArrayList<>();
        for (CompiledBenchmarkModule.CompiledIngressRoute route : benchmarkRouteRegistry.currentModule().ingressRoutes()) {
            routes.add(new RouteView(
                    route.routeId(),
                    route.method(),
                    route.inboundPath(),
                    route.targetService(),
                    serviceUrlResolver.resolve(route.targetService()) + route.targetPath(),
                    "Compiled from deposit-offers-gateway.dsl.yaml"
            ));
        }

        Object routesObj = dslLoader.openingModule().get("routes");
        if (routesObj instanceof List<?> dslRoutes) {
            for (Object item : dslRoutes) {
                if (!(item instanceof Map<?, ?> route)) {
                    continue;
                }
                String id = DslMaps.stringValue(route.get("id"));
                Map<String, Object> request = DslMaps.mapValue(route.get("request"));
                Map<String, Object> target = DslMaps.mapValue(route.get("target"));
                if (request == null) {
                    continue;
                }
                String path = DslMaps.stringValue(request.get("path"));
                String method = DslMaps.stringValue(request.get("method"));
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
                        DslMaps.stringValue(target != null ? target.get("service") : null),
                        target != null ? DslMaps.stringValue(target.get("path")) : null,
                        "Declared in deposit-opening-gateway.dsl.yaml"
                ));
            }
        }
        return routes;
    }

    private List<CapabilityView> capabilities() {
        List<CapabilityView> result = new ArrayList<>();
        for (CompiledBenchmarkModule.CompiledCapabilityRoute capability :
                benchmarkRouteRegistry.currentModule().capabilities()) {
            result.add(new CapabilityView(
                    capability.capabilityId(),
                    capability.method(),
                    capability.pathTemplate(),
                    capability.targetService() + " (" + capability.executionMode() + ")",
                    "Compiled from deposit-offers-gateway.dsl.yaml"
            ));
        }

        Object capabilitiesObj = dslLoader.openingModule().get("capabilities");
        if (capabilitiesObj instanceof List<?> capabilities) {
            for (Object item : capabilities) {
                if (!(item instanceof Map<?, ?> capability)) {
                    continue;
                }
                String id = DslMaps.stringValue(capability.get("id"));
                Map<String, Object> request = DslMaps.mapValue(capability.get("request"));
                if (request == null) {
                    continue;
                }
                String path = DslMaps.stringValue(request.get("path"));
                String method = DslMaps.stringValue(request.get("method"));
                if (path == null || result.stream().anyMatch(c -> c.path().equals(path))) {
                    continue;
                }
                result.add(new CapabilityView(
                        id,
                        method,
                        path,
                        DslMaps.stringValue(capability.get("providerSet")),
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
                    Map<String, Object> mapping = DslMaps.mapValue(route.get("mapping"));
                    Map<String, Object> request = DslMaps.mapValue(route.get("request"));
                    String publishPath = request != null ? DslMaps.stringValue(request.get("path")) : null;
                    bindings.add(new MessagingBindingView(
                            DslMaps.stringValue(route.get("id")),
                            "publish",
                            mapping != null ? DslMaps.stringValue(mapping.get("topicAlias")) : null,
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

    private String primaryListenerBindingId() {
        if (properties.kafka() == null || properties.kafka().listenerBindings() == null
                || properties.kafka().listenerBindings().isEmpty()) {
            return null;
        }
        return properties.kafka().listenerBindings().getFirst();
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
