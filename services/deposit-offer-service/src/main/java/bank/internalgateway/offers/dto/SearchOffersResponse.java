package bank.internalgateway.offers.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SearchOffersResponse(
        String organizationId,
        String accountId,
        String organizationDisplayName,
        String accountCurrency,
        List<OfferResult> offers,
        Instant calculatedAt
) {
    public record OfferResult(
            String offerId,
            String source,
            String productCode,
            BigDecimal rate,
            int termMonths,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currency,
            Instant expiresAt
    ) {
    }
}
