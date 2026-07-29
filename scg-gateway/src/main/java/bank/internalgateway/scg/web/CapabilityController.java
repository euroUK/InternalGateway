package bank.internalgateway.scg.web;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.dsl.StaticCapabilityRenderer;
import bank.internalgateway.scg.observability.RequestTraceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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

    @GetMapping(value = "/internal/capabilities/accounts/{accountId}/deposit-context",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> accountDepositContext(
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/accounts/" + accountId + "/deposit-context";
        return render(path, correlationId, "accountId=" + accountId);
    }

    @GetMapping(value = "/internal/capabilities/organizations/{organizationId}/display-info",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> organizationDisplayInfo(
            @PathVariable("organizationId") String organizationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/organizations/" + organizationId + "/display-info";
        return render(path, correlationId, "organizationId=" + organizationId);
    }

    private Mono<Map<String, Object>> render(String path, String correlationId, String detail) {
        long started = System.currentTimeMillis();
        CompiledBenchmarkModule.CompiledCapabilityRoute capability =
                benchmarkRouteRegistry.currentModule().requireCapabilityByPath(path);
        return Mono.fromCallable(() -> StaticCapabilityRenderer.render(capability, path))
                .doFinally(signal -> requestTraceService.recordSimple(
                        "capability",
                        capability.method(),
                        path,
                        capability.targetService(),
                        capability.targetUrl(),
                        correlationId,
                        "200",
                        System.currentTimeMillis() - started,
                        detail
                ));
    }
}
