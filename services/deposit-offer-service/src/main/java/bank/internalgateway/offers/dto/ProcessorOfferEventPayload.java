package bank.internalgateway.offers.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessorOfferEventPayload(
        String eventId,
        String eventType,
        String processorOfferId,
        long processorOfferVersion,
        String productCode,
        BigDecimal rate,
        int termMonths,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String currency,
        String occurredAt
) {
}
