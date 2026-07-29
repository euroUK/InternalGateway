package bank.internalgateway.gateway.web;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.dsl.StaticCapabilityRenderer;
import bank.internalgateway.gateway.observability.RequestTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CapabilityController {

    private final BenchmarkRouteRegistry benchmarkRouteRegistry;
    private final RequestTraceService requestTraceService;

    public CapabilityController(
            BenchmarkRouteRegistry benchmarkRouteRegistry,
            RequestTraceService requestTraceService) {
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
        this.requestTraceService = requestTraceService;
    }

    @GetMapping("/internal/capabilities/accounts/{accountId}/deposit-context")
    public ResponseEntity<Map<String, Object>> accountDepositContext(
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/accounts/" + accountId + "/deposit-context";
        return renderCapability(path, correlationId, "accountId=" + accountId);
    }

    @GetMapping("/internal/capabilities/organizations/{organizationId}/display-info")
    public ResponseEntity<Map<String, Object>> organizationDisplayInfo(
            @PathVariable("organizationId") String organizationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/organizations/" + organizationId + "/display-info";
        return renderCapability(path, correlationId, "organizationId=" + organizationId);
    }

    private ResponseEntity<Map<String, Object>> renderCapability(
            String path,
            String correlationId,
            String detail) {
        long started = System.currentTimeMillis();
        CompiledBenchmarkModule.CompiledCapabilityRoute capability =
                benchmarkRouteRegistry.currentModule().requireCapabilityByPath(path);
        try {
            return ResponseEntity.ok(StaticCapabilityRenderer.render(capability, path));
        } finally {
            requestTraceService.recordSimple(
                    "capability",
                    capability.method(),
                    path,
                    capability.targetService(),
                    capability.targetUrl(),
                    correlationId,
                    "200",
                    System.currentTimeMillis() - started,
                    detail
            );
        }
    }
}
