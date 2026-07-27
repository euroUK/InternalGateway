package bank.internalgateway.processor.dto;

import java.math.BigDecimal;

public record ExternalProcessorOfferMessage(
        String offerExternalId,
        long revision,
        String productExternalCode,
        BigDecimal interestRatePercent,
        int durationMonths,
        AmountRange amountRange,
        String currencyCode
) {
    public record AmountRange(
            BigDecimal from,
            BigDecimal to
    ) {
    }
}
