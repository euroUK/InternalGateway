package bank.internalgateway.gateway.web;

import bank.internalgateway.gateway.messaging.EventMappingRegistry;
import bank.internalgateway.gateway.observability.GatewayConfigViewService;
import bank.internalgateway.gateway.observability.RequestTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/admin")
public class AdminController {

    private final GatewayConfigViewService configViewService;
    private final RequestTraceService requestTraceService;
    private final EventMappingRegistry eventMappingRegistry;

    public AdminController(
            GatewayConfigViewService configViewService,
            RequestTraceService requestTraceService,
            EventMappingRegistry eventMappingRegistry) {
        this.configViewService = configViewService;
        this.requestTraceService = requestTraceService;
        this.eventMappingRegistry = eventMappingRegistry;
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

    @PostMapping("/mappings/reload")
    public ResponseEntity<Map<String, Object>> reloadMappings() throws Exception {
        eventMappingRegistry.reload();
        return ResponseEntity.ok(Map.of(
                "status", "reloaded",
                "mappingCount", eventMappingRegistry.registeredMappings().size(),
                "mappings", eventMappingRegistry.registeredMappings()
        ));
    }
}
