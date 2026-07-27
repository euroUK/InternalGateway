package bank.internalgateway.gateway.web;

import bank.internalgateway.gateway.observability.RequestTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class CapabilityController {

    private final RequestTraceService requestTraceService;

    public CapabilityController(RequestTraceService requestTraceService) {
        this.requestTraceService = requestTraceService;
    }

    @GetMapping("/internal/capabilities/accounts/{accountId}/deposit-context")
    public ResponseEntity<Map<String, Object>> accountDepositContext(
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/accounts/" + accountId + "/deposit-context";
        long started = System.currentTimeMillis();
        try {
            return ResponseEntity.ok(Map.of(
                    "accountId", accountId,
                    "currency", "RUB",
                    "availableBalance", new BigDecimal("1500000.00"),
                    "debitAllowed", true,
                    "organizationId", "org-demo-001",
                    "snapshotAt", java.time.Instant.now().toString()
            ));
        } finally {
            requestTraceService.recordSimple(
                    "capability",
                    "GET",
                    path,
                    "account-context-provider",
                    "stub://account-lite-or-core",
                    correlationId,
                    "200",
                    System.currentTimeMillis() - started,
                    "accountId=" + accountId
            );
        }
    }

    @GetMapping("/internal/capabilities/organizations/{organizationId}/display-info")
    public ResponseEntity<Map<String, Object>> organizationDisplayInfo(
            @PathVariable("organizationId") String organizationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String path = "/internal/capabilities/organizations/" + organizationId + "/display-info";
        long started = System.currentTimeMillis();
        try {
            return ResponseEntity.ok(Map.of(
                    "organizationId", organizationId,
                    "displayName", "Demo Organization LLC",
                    "legalForm", "LLC"
            ));
        } finally {
            requestTraceService.recordSimple(
                    "capability",
                    "GET",
                    path,
                    "organization-directory-provider",
                    "stub://organization-lite-or-core",
                    correlationId,
                    "200",
                    System.currentTimeMillis() - started,
                    "organizationId=" + organizationId
            );
        }
    }
}
