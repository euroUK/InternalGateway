package bank.internalgateway.gateway.web;

import bank.internalgateway.gateway.test.TestProcessorRegistry;
import bank.internalgateway.gateway.test.TestScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/admin/test")
public class AdminTestController {

    private final TestScenarioService testScenarioService;

    public AdminTestController(TestScenarioService testScenarioService) {
        this.testScenarioService = testScenarioService;
    }

    @GetMapping("/processors")
    public List<TestProcessorRegistry.TestProcessorRegistration> processors() {
        return testScenarioService.registeredProcessors();
    }

    @PostMapping("/processors")
    public TestProcessorRegistry.TestProcessorRegistration registerProcessor(
            @RequestBody TestProcessorRegistry.TestProcessorRegistration registration) {
        return testScenarioService.registerProcessor(registration);
    }

    @GetMapping("/resilience/stats")
    public Map<String, Object> resilienceStats() {
        return testScenarioService.resilienceStats();
    }

    @PostMapping("/scenarios/dedup")
    public ResponseEntity<Map<String, Object>> runDedup(
            @RequestParam(defaultValue = "test-processor-offer-lifecycle") String bindingId) throws Exception {
        return ResponseEntity.ok(testScenarioService.runDedupScenario(bindingId));
    }

    @PostMapping("/scenarios/retry")
    public ResponseEntity<Map<String, Object>> runRetry(
            @RequestParam(defaultValue = "test-processor-offer-lifecycle") String bindingId,
            @RequestParam(defaultValue = "2") int failCount) {
        return ResponseEntity.ok(testScenarioService.runRetryScenario(bindingId, failCount));
    }

    @PostMapping("/scenarios/rate-limit")
    public ResponseEntity<Map<String, Object>> runRateLimit(
            @RequestParam(defaultValue = "test-processor-offer-lifecycle") String bindingId,
            @RequestParam(defaultValue = "6") int burstCount) {
        return ResponseEntity.ok(testScenarioService.runRateLimitScenario(bindingId, burstCount));
    }
}
