package bank.internalgateway.offers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record FixedOfferRequest(
        @NotBlank String organizationId,
        @NotBlank String accountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull @Positive Integer termMonths,
        String organizationDisplayName
) {
}
