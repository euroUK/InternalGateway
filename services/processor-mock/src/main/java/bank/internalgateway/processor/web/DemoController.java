package bank.internalgateway.processor.web;

import bank.internalgateway.processor.dto.ExternalProcessorOfferMessage;
import bank.internalgateway.processor.kafka.ProcessorEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final ProcessorEventPublisher publisher;

    public DemoController(ProcessorEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/publish-offer-event")
    public ResponseEntity<Map<String, String>> publishOfferEvent(@RequestBody DemoPublishRequest request) {
        String messageType = toProcessorMessageType(request.eventType());
        ExternalProcessorOfferMessage message = new ExternalProcessorOfferMessage(
                request.processorOfferId(),
                request.processorOfferVersion(),
                request.productCode(),
                toInterestRatePercent(request.rate()),
                request.termMonths(),
                new ExternalProcessorOfferMessage.AmountRange(request.minAmount(), request.maxAmount()),
                request.currency()
        );
        publisher.publishExternal(messageType, message);
        return ResponseEntity.accepted().body(Map.of(
                "messageType", messageType,
                "processorOfferId", request.processorOfferId(),
                "note", "Published in processor-native format; Gateway maps to internal canonical model"
        ));
    }

    private String toProcessorMessageType(String uiEventType) {
        return switch (uiEventType) {
            case "DepositOfferCreated", "OFFER_CREATED" -> "OFFER_CREATED";
            case "DepositOfferUpdated", "OFFER_UPDATED" -> "OFFER_UPDATED";
            case "DepositOfferClosed", "OFFER_CLOSED" -> "OFFER_CLOSED";
            default -> uiEventType;
        };
    }

    private BigDecimal toInterestRatePercent(BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ONE) < 0) {
            return rate.multiply(BigDecimal.valueOf(100)).setScale(3, RoundingMode.HALF_UP);
        }
        return rate;
    }

    public record DemoPublishRequest(
            String eventType,
            String processorOfferId,
            long processorOfferVersion,
            String productCode,
            BigDecimal rate,
            int termMonths,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currency
    ) {
    }
}
