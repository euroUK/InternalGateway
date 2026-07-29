package bank.internalgateway.dsl;

import java.util.List;
import java.util.Map;

public record CompiledBenchmarkModule(
        String moduleName,
        String moduleVersion,
        EnvelopePolicy envelopePolicy,
        List<CompiledIngressRoute> ingressRoutes,
        List<CompiledCapabilityRoute> capabilities
) {
    public CompiledIngressRoute requireIngressByPath(String method, String path) {
        return ingressRoutes.stream()
                .filter(route -> route.method().equalsIgnoreCase(method) && route.inboundPath().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ingress route for " + method + " " + path));
    }

    public CompiledCapabilityRoute requireCapabilityByPath(String path) {
        return capabilities.stream()
                .filter(capability -> PathTemplate.matches(capability.pathTemplate(), path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No capability route for " + path));
    }

    public CompiledCapabilityRoute requireCapabilityById(String capabilityId) {
        return capabilities.stream()
                .filter(capability -> capability.capabilityId().equals(capabilityId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No capability id: " + capabilityId));
    }

    public record EnvelopePolicy(
            String issuer,
            int ttlSeconds,
            List<String> claims,
            String businessControlEvidenceId
    ) {
    }

    public record CompiledAdapterStep(
            String capabilityId,
            String method,
            String pathTemplate
    ) {
    }

    public record CompiledIngressRoute(
            String routeId,
            String method,
            String inboundPath,
            String identityContext,
            String targetService,
            String targetMethod,
            String targetPath,
            boolean businessControlStub,
            String businessControlEvidenceId,
            CompiledAdapterStep adapter,
            Map<String, String> responseMapping
    ) {
        public boolean isEnriched() {
            return adapter != null;
        }
    }

    public record CompiledCapabilityRoute(
            String capabilityId,
            String method,
            String pathTemplate,
            String executionMode,
            Map<String, Object> responseTemplate,
            String targetService,
            String targetUrl
    ) {
    }
}
