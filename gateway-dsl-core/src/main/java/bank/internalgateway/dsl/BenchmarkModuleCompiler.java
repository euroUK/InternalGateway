package bank.internalgateway.dsl;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BenchmarkModuleCompiler {

    public static final String OFFERS_DSL_FILE = "deposit-offers-gateway.dsl.yaml";

    private BenchmarkModuleCompiler() {
    }

    public static CompiledBenchmarkModule compileFromDirectory(Path dslDir) throws IOException {
        Map<String, Object> root = loadYaml(dslDir.resolve(OFFERS_DSL_FILE));
        return compile(root);
    }

    public static CompiledBenchmarkModule compile(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            throw new IllegalArgumentException("Offers DSL is empty");
        }

        Map<String, Object> metadata = DslMaps.mapValue(root.get("metadata"));
        String moduleName = metadata != null ? DslMaps.stringValue(metadata.get("name")) : "deposit-offers";
        String moduleVersion = metadata != null ? DslMaps.stringValue(metadata.get("version")) : "1.0.0";

        Map<String, Object> identity = DslMaps.mapValue(root.get("identity"));
        Map<String, Object> forwarded = identity != null
                ? DslMaps.mapValue(identity.get("forwardedEnvelope"))
                : null;
        String issuer = forwarded != null && forwarded.get("issuer") != null
                ? DslMaps.stringValue(forwarded.get("issuer"))
                : "internal-gateway";
        int ttlSeconds = forwarded != null
                ? DslMaps.intValue(forwarded.get("ttl"), 30)
                : 30;
        List<String> claims = DslMaps.parseEnvelopeClaims(identity);
        if (claims.isEmpty()) {
            claims = List.of("subjectId", "organizationId", "correlationId", "operationId", "businessControlEvidenceId");
        }

        List<CompiledBenchmarkModule.CompiledCapabilityRoute> capabilities = compileCapabilities(root);
        List<CompiledBenchmarkModule.CompiledIngressRoute> ingressRoutes = compileIngressRoutes(root, capabilities);

        String evidenceId = ingressRoutes.stream()
                .map(CompiledBenchmarkModule.CompiledIngressRoute::businessControlEvidenceId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse("poc-stub-passed");

        CompiledBenchmarkModule.EnvelopePolicy envelopePolicy =
                new CompiledBenchmarkModule.EnvelopePolicy(issuer, ttlSeconds, claims, evidenceId);

        validate(ingressRoutes, capabilities);
        return new CompiledBenchmarkModule(
                moduleName != null ? moduleName : "deposit-offers",
                moduleVersion != null ? moduleVersion : "1.0.0",
                envelopePolicy,
                List.copyOf(ingressRoutes),
                List.copyOf(capabilities)
        );
    }

    private static List<CompiledBenchmarkModule.CompiledIngressRoute> compileIngressRoutes(
            Map<String, Object> root,
            List<CompiledBenchmarkModule.CompiledCapabilityRoute> capabilities) {
        Object routesObj = root.get("routes");
        if (!(routesObj instanceof List<?> routes)) {
            throw new IllegalArgumentException("offers DSL must declare routes[]");
        }
        Map<String, CompiledBenchmarkModule.CompiledCapabilityRoute> capabilitiesById = new LinkedHashMap<>();
        for (CompiledBenchmarkModule.CompiledCapabilityRoute capability : capabilities) {
            capabilitiesById.put(capability.capabilityId(), capability);
        }

        List<CompiledBenchmarkModule.CompiledIngressRoute> result = new ArrayList<>();
        for (Object item : routes) {
            Map<String, Object> route = DslMaps.mapValue(item);
            if (route == null) {
                continue;
            }
            String routeId = required(route, "id");
            Map<String, Object> request = requiredMap(route, "request");
            Map<String, Object> target = requiredMap(route, "target");
            Map<String, Object> validation = DslMaps.mapValue(route.get("validation"));
            Map<String, Object> businessControl = validation != null
                    ? DslMaps.mapValue(validation.get("businessControl"))
                    : null;

            String method = required(request, "method").toUpperCase();
            String inboundPath = required(request, "path");
            String targetService = required(target, "service");
            String targetMethod = target.get("method") != null
                    ? DslMaps.stringValue(target.get("method")).toUpperCase()
                    : method;
            String targetPath = required(target, "path");
            boolean stub = businessControl == null
                    || "stub".equalsIgnoreCase(DslMaps.stringValue(businessControl.get("effect")));
            String evidenceId = businessControl != null && businessControl.get("evidenceId") != null
                    ? DslMaps.stringValue(businessControl.get("evidenceId"))
                    : "poc-stub-passed";

            CompiledBenchmarkModule.CompiledAdapterStep adapter =
                    compileAdapter(routeId, DslMaps.mapValue(route.get("adapter")), capabilitiesById);
            Map<String, String> responseMapping = compileResponseMapping(route.get("responseMapping"));
            if (adapter != null && responseMapping.isEmpty()) {
                throw new IllegalArgumentException(
                        "Route " + routeId + " with adapter requires responseMapping");
            }

            result.add(new CompiledBenchmarkModule.CompiledIngressRoute(
                    routeId,
                    method,
                    inboundPath,
                    DslMaps.stringValue(route.get("identityContext")) != null
                            ? DslMaps.stringValue(route.get("identityContext"))
                            : "bankUser",
                    targetService,
                    targetMethod,
                    targetPath,
                    stub,
                    evidenceId,
                    adapter,
                    responseMapping
            ));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("offers DSL routes[] must not be empty");
        }
        return result;
    }

    private static CompiledBenchmarkModule.CompiledAdapterStep compileAdapter(
            String routeId,
            Map<String, Object> adapter,
            Map<String, CompiledBenchmarkModule.CompiledCapabilityRoute> capabilitiesById) {
        if (adapter == null || adapter.isEmpty()) {
            return null;
        }
        String capabilityId = required(adapter, "capability");
        CompiledBenchmarkModule.CompiledCapabilityRoute capability = capabilitiesById.get(capabilityId);
        if (capability == null) {
            throw new IllegalArgumentException(
                    "Route " + routeId + " adapter.capability not found: " + capabilityId);
        }
        return new CompiledBenchmarkModule.CompiledAdapterStep(
                capabilityId,
                capability.method(),
                capability.pathTemplate()
        );
    }

    private static Map<String, String> compileResponseMapping(Object mappingObj) {
        Map<String, Object> mapping = DslMaps.mapValue(mappingObj);
        if (mapping == null || mapping.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            String value = DslMaps.stringValue(entry.getValue());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("responseMapping." + entry.getKey() + " must be a string");
            }
            result.put(entry.getKey(), value);
        }
        return Map.copyOf(result);
    }

    private static List<CompiledBenchmarkModule.CompiledCapabilityRoute> compileCapabilities(Map<String, Object> root) {
        Object capabilitiesObj = root.get("capabilities");
        if (!(capabilitiesObj instanceof List<?> capabilities)) {
            throw new IllegalArgumentException("offers DSL must declare capabilities[]");
        }
        List<CompiledBenchmarkModule.CompiledCapabilityRoute> result = new ArrayList<>();
        for (Object item : capabilities) {
            Map<String, Object> capability = DslMaps.mapValue(item);
            if (capability == null) {
                continue;
            }
            String capabilityId = required(capability, "id");
            Map<String, Object> request = requiredMap(capability, "request");
            String method = required(request, "method").toUpperCase();
            String pathTemplate = required(request, "path");
            String executionMode = capability.get("executionMode") != null
                    ? DslMaps.stringValue(capability.get("executionMode"))
                    : "static-stub";
            Map<String, Object> responseTemplate = DslMaps.objectMap(capability.get("responseTemplate"));
            if (responseTemplate.isEmpty()) {
                throw new IllegalArgumentException("capability " + capabilityId + " requires responseTemplate");
            }
            result.add(new CompiledBenchmarkModule.CompiledCapabilityRoute(
                    capabilityId,
                    method,
                    pathTemplate,
                    executionMode,
                    Map.copyOf(new LinkedHashMap<>(responseTemplate)),
                    DslMaps.stringValue(capability.get("targetService")),
                    DslMaps.stringValue(capability.get("targetUrl"))
            ));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("offers DSL capabilities[] must not be empty");
        }
        return result;
    }

    private static void validate(
            List<CompiledBenchmarkModule.CompiledIngressRoute> ingressRoutes,
            List<CompiledBenchmarkModule.CompiledCapabilityRoute> capabilities) {
        Set<String> routeIds = new HashSet<>();
        Set<String> routeKeys = new HashSet<>();
        for (CompiledBenchmarkModule.CompiledIngressRoute route : ingressRoutes) {
            if (!routeIds.add(route.routeId())) {
                throw new IllegalArgumentException("Duplicate route id: " + route.routeId());
            }
            String key = route.method() + " " + route.inboundPath();
            if (!routeKeys.add(key)) {
                throw new IllegalArgumentException("Duplicate ingress route: " + key);
            }
            if (route.targetService() == null || route.targetService().isBlank()) {
                throw new IllegalArgumentException("Route " + route.routeId() + " missing target.service");
            }
            if (!route.targetPath().startsWith("/")) {
                throw new IllegalArgumentException("Route " + route.routeId() + " target.path must start with /");
            }
        }

        Set<String> capabilityIds = new HashSet<>();
        Set<String> capabilityKeys = new HashSet<>();
        for (CompiledBenchmarkModule.CompiledCapabilityRoute capability : capabilities) {
            if (!capabilityIds.add(capability.capabilityId())) {
                throw new IllegalArgumentException("Duplicate capability id: " + capability.capabilityId());
            }
            String key = capability.method() + " " + capability.pathTemplate();
            if (!capabilityKeys.add(key)) {
                throw new IllegalArgumentException("Duplicate capability route: " + key);
            }
            if (!"static-stub".equals(capability.executionMode())) {
                throw new IllegalArgumentException(
                        "Unsupported capability executionMode for benchmark: " + capability.executionMode());
            }
        }
    }

    private static String required(Map<String, Object> map, String key) {
        String value = DslMaps.stringValue(map.get(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private static Map<String, Object> requiredMap(Map<String, Object> map, String key) {
        Map<String, Object> value = DslMaps.mapValue(map.get(key));
        if (value == null) {
            throw new IllegalArgumentException("Missing required object: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Offers DSL not found: " + path);
        }
        Yaml yaml = new Yaml();
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = yaml.load(input);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Offers DSL root must be a mapping: " + path);
            }
            return (Map<String, Object>) map;
        }
    }
}
