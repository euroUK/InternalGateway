package bank.internalgateway.offers.web;

import bank.internalgateway.offers.dto.SearchOffersRequest;
import bank.internalgateway.offers.dto.SearchOffersResponse;
import bank.internalgateway.offers.service.OfferSearchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/offers")
public class OfferSearchController {

    private final OfferSearchService offerSearchService;

    public OfferSearchController(OfferSearchService offerSearchService) {
        this.offerSearchService = offerSearchService;
    }

    @PostMapping("/search")
    public ResponseEntity<SearchOffersResponse> search(
            @Valid @RequestBody SearchOffersRequest request,
            @RequestHeader(value = "X-Identity-Envelope", required = false) String identityEnvelope,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (identityEnvelope == null || identityEnvelope.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(offerSearchService.search(request, correlationId));
    }
}
