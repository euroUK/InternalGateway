package bank.internalgateway.scg.web;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.scg.observability.RequestTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal/admin")
public class AdminController {

    private final RequestTraceService requestTraceService;
    private final BenchmarkRouteRegistry benchmarkRouteRegistry;

    public AdminController(
            RequestTraceService requestTraceService,
            BenchmarkRouteRegistry benchmarkRouteRegistry) {
        this.requestTraceService = requestTraceService;
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
    }

    @GetMapping("/stats")
    public Mono<RequestTraceService.RequestStats> stats() {
        return Mono.fromCallable(requestTraceService::stats);
    }

    @GetMapping("/requests")
    public Mono<Map<String, Object>> requests(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> Map.of(
                "items", requestTraceService.recent(limit),
                "stats", requestTraceService.stats()
        ));
    }

    @GetMapping("/dsl/config")
    public Mono<Map<String, Object>> dslConfig() {
        return Mono.fromCallable(() -> dslSnapshot(benchmarkRouteRegistry.currentSnapshot()));
    }

    @PostMapping("/dsl/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reloadDsl() {
        return Mono.fromCallable(() -> {
            BenchmarkRouteRegistry.ReloadResult result = benchmarkRouteRegistry.reload();
            if (result.success()) {
                return ResponseEntity.ok(Map.of(
                        "status", "reloaded",
                        "success", true,
                        "config", dslSnapshot(result.current())
                ));
            }
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "rejected",
                    "success", false,
                    "error", result.error() != null ? result.error() : "reload failed",
                    "config", dslSnapshot(result.current())
            ));
        });
    }

    private Map<String, Object> dslSnapshot(BenchmarkRouteRegistry.Snapshot snapshot) {
        CompiledBenchmarkModule module = snapshot.module();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("moduleName", module.moduleName());
        view.put("moduleVersion", module.moduleVersion());
        view.put("version", snapshot.version());
        view.put("status", snapshot.status());
        view.put("loadedAt", snapshot.loadedAt().toString());
        view.put("routeCount", module.ingressRoutes().size());
        view.put("capabilityCount", module.capabilities().size());
        view.put("ingressRoutes", module.ingressRoutes());
        view.put("capabilities", module.capabilities());
        view.put("envelopePolicy", module.envelopePolicy());
        return view;
    }
}
