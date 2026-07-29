package bank.internalgateway.scg.config;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DslRouteDefinitionLocator implements RouteDefinitionLocator {

    public static final String META_ROUTE_ID = "benchmarkRouteId";
    public static final String META_INBOUND_PATH = "inboundPath";
    public static final String META_TARGET_SERVICE = "targetService";
    public static final String META_TARGET_PATH = "targetPath";
    public static final String META_TARGET_METHOD = "targetMethod";
    public static final String META_EVIDENCE_ID = "businessControlEvidenceId";
    public static final String META_ADAPTER_METHOD = "adapterMethod";
    public static final String META_ADAPTER_PATH = "adapterPathTemplate";
    public static final String META_RESPONSE_MAPPING = "responseMappingJson";

    private final BenchmarkRouteRegistry benchmarkRouteRegistry;
    private final ScgGatewayProperties properties;
    private final ObjectMapper objectMapper;

    public DslRouteDefinitionLocator(
            BenchmarkRouteRegistry benchmarkRouteRegistry,
            ScgGatewayProperties properties,
            ObjectMapper objectMapper) {
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        List<RouteDefinition> definitions = new ArrayList<>();
        for (CompiledBenchmarkModule.CompiledIngressRoute route :
                benchmarkRouteRegistry.currentModule().ingressRoutes()) {
            definitions.add(toDefinition(route));
        }
        return Flux.fromIterable(definitions);
    }

    private RouteDefinition toDefinition(CompiledBenchmarkModule.CompiledIngressRoute route) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(route.routeId());
        definition.setUri(URI.create(resolveServiceUrl(route.targetService())));
        definition.setPredicates(List.of(
                new PredicateDefinition("Method=" + route.method()),
                new PredicateDefinition("Path=" + route.inboundPath())
        ));

        FilterDefinition filter = new FilterDefinition();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_ROUTE_ID, route.routeId());
        metadata.put(META_INBOUND_PATH, route.inboundPath());
        metadata.put(META_TARGET_SERVICE, route.targetService());
        metadata.put(META_TARGET_PATH, route.targetPath());
        metadata.put(META_TARGET_METHOD, route.targetMethod());
        metadata.put(META_EVIDENCE_ID, route.businessControlEvidenceId());

        if (route.isEnriched()) {
            filter.setName("EnrichedDeposit");
            filter.addArg(META_ROUTE_ID, route.routeId());
            filter.addArg(META_INBOUND_PATH, route.inboundPath());
            filter.addArg(META_TARGET_SERVICE, route.targetService());
            filter.addArg(META_TARGET_PATH, route.targetPath());
            filter.addArg(META_TARGET_METHOD, route.targetMethod());
            filter.addArg(META_EVIDENCE_ID, route.businessControlEvidenceId());
            filter.addArg(META_ADAPTER_METHOD, route.adapter().method());
            filter.addArg(META_ADAPTER_PATH, route.adapter().pathTemplate());
            filter.addArg(META_RESPONSE_MAPPING, toJson(route.responseMapping()));
            metadata.put(META_ADAPTER_METHOD, route.adapter().method());
            metadata.put(META_ADAPTER_PATH, route.adapter().pathTemplate());
            metadata.put(META_RESPONSE_MAPPING, route.responseMapping());
        } else {
            filter.setName("BenchmarkIngress");
            filter.addArg(META_ROUTE_ID, route.routeId());
            filter.addArg(META_INBOUND_PATH, route.inboundPath());
            filter.addArg(META_TARGET_SERVICE, route.targetService());
            filter.addArg(META_TARGET_PATH, route.targetPath());
            filter.addArg(META_TARGET_METHOD, route.targetMethod());
            filter.addArg(META_EVIDENCE_ID, route.businessControlEvidenceId());
        }

        definition.setFilters(List.of(filter));
        definition.setMetadata(metadata);
        return definition;
    }

    private String toJson(Map<String, String> mapping) {
        try {
            return objectMapper.writeValueAsString(mapping != null ? mapping : Map.of());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize responseMapping", ex);
        }
    }

    private String resolveServiceUrl(String serviceAlias) {
        if ("deposit-offer-service".equals(serviceAlias)
                || serviceAlias == null
                || serviceAlias.isBlank()) {
            return properties.depositOfferServiceUrl();
        }
        throw new IllegalArgumentException("Unknown SCG service alias: " + serviceAlias);
    }
}
