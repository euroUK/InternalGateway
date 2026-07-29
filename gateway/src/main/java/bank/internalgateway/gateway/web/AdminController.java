package bank.internalgateway.gateway.web;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.dsl.CompiledBenchmarkModule;
import bank.internalgateway.gateway.dsl.DslLoader;
import bank.internalgateway.gateway.messaging.EventMappingRegistry;
import bank.internalgateway.gateway.observability.GatewayConfigViewService;
import bank.internalgateway.gateway.observability.RequestTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal/admin")
public class AdminController {

    private final GatewayConfigViewService configViewService;
    private final RequestTraceService requestTraceService;
    private final EventMappingRegistry eventMappingRegistry;
    private final BenchmarkRouteRegistry benchmarkRouteRegistry;
    private final DslLoader dslLoader;

    public AdminController(
            GatewayConfigViewService configViewService,
            RequestTraceService requestTraceService,
            EventMappingRegistry eventMappingRegistry,
            BenchmarkRouteRegistry benchmarkRouteRegistry,
            DslLoader dslLoader) {
        this.configViewService = configViewService;
        this.requestTraceService = requestTraceService;
        this.eventMappingRegistry = eventMappingRegistry;
        this.benchmarkRouteRegistry = benchmarkRouteRegistry;
        this.dslLoader = dslLoader;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "50") int limit) {
        return Map.of(
                "config", configViewService.build(),
                "stats", requestTraceService.stats(),
                "recentRequests", requestTraceService.recent(limit)
        );
    }

    @GetMapping("/config")
    public GatewayConfigViewService.GatewayConfigView config() {
        return configViewService.build();
    }

    @GetMapping("/stats")
    public RequestTraceService.RequestStats stats() {
        return requestTraceService.stats();
    }

    @GetMapping("/requests")
    public Map<String, Object> requests(@RequestParam(defaultValue = "100") int limit) {
        return Map.of(
                "items", requestTraceService.recent(limit),
                "stats", requestTraceService.stats()
        );
    }

    @GetMapping("/dsl/config")
    public Map<String, Object> dslConfig() {
        return dslSnapshot(benchmarkRouteRegistry.currentSnapshot());
    }

    @PostMapping("/dsl/reload")
    public ResponseEntity<Map<String, Object>> reloadDsl() throws Exception {
        BenchmarkRouteRegistry.ReloadResult result = benchmarkRouteRegistry.reload();
        if (result.success()) {
            dslLoader.reloadOffersRaw();
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
    }

    @PostMapping("/mappings/reload")
    public ResponseEntity<Map<String, Object>> reloadMappings() throws Exception {
        eventMappingRegistry.reload();
        return ResponseEntity.ok(Map.of(
                "status", "reloaded",
                "mappingCount", eventMappingRegistry.registeredMappings().size(),
                "mappings", eventMappingRegistry.registeredMappings()
        ));
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
