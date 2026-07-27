package bank.internalgateway.offers.test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/test")
public class TestFaultController {

    private final TestFaultState faultState;

    public TestFaultController(TestFaultState faultState) {
        this.faultState = faultState;
    }

    @GetMapping("/fault")
    public TestFaultState.FaultSnapshot status() {
        return faultState.snapshot();
    }

    @PostMapping("/fault")
    public ResponseEntity<Map<String, Object>> configure(@RequestBody FaultConfig request) {
        faultState.configure(request.failNextRequests(), request.statusCode());
        return ResponseEntity.ok(Map.of(
                "failRemaining", faultState.snapshot().failRemaining(),
                "statusCode", faultState.snapshot().failStatus()
        ));
    }

    @PostMapping("/fault/reset")
    public ResponseEntity<Map<String, String>> reset() {
        faultState.reset();
        return ResponseEntity.ok(Map.of("status", "reset"));
    }

    public record FaultConfig(int failNextRequests, int statusCode) {
    }
}
