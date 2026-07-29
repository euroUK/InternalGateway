package bank.internalgateway.offers.web;

import bank.internalgateway.offers.dto.FixedOfferRequest;
import bank.internalgateway.offers.dto.FixedOfferResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/internal/v1/offers")
public class FixedOfferController {

    private static final FixedOfferResponse FIXED_OFFER = new FixedOfferResponse(
            "FIXED-DEP-12",
            new BigDecimal("0.125"),
            12,
            "RUB",
            100_000L,
            "AVAILABLE"
    );

    @PostMapping("/fixed")
    public ResponseEntity<FixedOfferResponse> fixed(
            @Valid @RequestBody FixedOfferRequest request,
            @RequestHeader(value = "X-Identity-Envelope", required = false) String identityEnvelope,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (identityEnvelope == null || identityEnvelope.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(FIXED_OFFER);
    }
}
