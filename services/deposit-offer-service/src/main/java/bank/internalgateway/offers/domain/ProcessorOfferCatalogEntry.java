package bank.internalgateway.offers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "processor_offer_catalog")
public class ProcessorOfferCatalogEntry {

    @Id
    private String processorOfferId;

    @Column(nullable = false)
    private long processorOfferVersion;

    @Column(nullable = false)
    private String productCode;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(nullable = false)
    private int termMonths;

    @Column(nullable = false)
    private BigDecimal minAmount;

    @Column(nullable = false)
    private BigDecimal maxAmount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ProcessorOfferCatalogEntry() {
    }

    public ProcessorOfferCatalogEntry(
            String processorOfferId,
            long processorOfferVersion,
            String productCode,
            BigDecimal rate,
            int termMonths,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currency,
            boolean active,
            Instant occurredAt,
            Instant updatedAt) {
        this.processorOfferId = processorOfferId;
        this.processorOfferVersion = processorOfferVersion;
        this.productCode = productCode;
        this.rate = rate;
        this.termMonths = termMonths;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.currency = currency;
        this.active = active;
        this.occurredAt = occurredAt;
        this.updatedAt = updatedAt;
    }

    public String getProcessorOfferId() {
        return processorOfferId;
    }

    public long getProcessorOfferVersion() {
        return processorOfferVersion;
    }

    public String getProductCode() {
        return productCode;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void applyUpdate(
            long newVersion,
            String productCode,
            BigDecimal rate,
            int termMonths,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String currency,
            boolean active,
            Instant occurredAt,
            Instant updatedAt) {
        this.processorOfferVersion = newVersion;
        this.productCode = productCode;
        this.rate = rate;
        this.termMonths = termMonths;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.currency = currency;
        this.active = active;
        this.occurredAt = occurredAt;
        this.updatedAt = updatedAt;
    }
}
