package bank.internalgateway.offers.dto;

import java.math.BigDecimal;

public record FixedOfferResponse(
        String productCode,
        BigDecimal rate,
        int termMonths,
        String currency,
        long minAmount,
        String status
) {
}
