package bank.internalgateway.offers.web;

import bank.internalgateway.offers.dto.ProcessorOfferEventPayload;
import bank.internalgateway.offers.service.OfferSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/events/deposit-processor")
public class ProcessorEventController {

    private final OfferSearchService offerSearchService;

    public ProcessorEventController(OfferSearchService offerSearchService) {
        this.offerSearchService = offerSearchService;
    }

    @PostMapping("/offer-created")
    public ResponseEntity<Void> offerCreated(@RequestBody ProcessorOfferEventPayload payload) {
        offerSearchService.handleCreated(payload);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/offer-updated")
    public ResponseEntity<Void> offerUpdated(@RequestBody ProcessorOfferEventPayload payload) {
        offerSearchService.handleUpdated(payload);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/offer-closed")
    public ResponseEntity<Void> offerClosed(@RequestBody ProcessorOfferEventPayload payload) {
        offerSearchService.handleClosed(payload);
        return ResponseEntity.accepted().build();
    }
}
