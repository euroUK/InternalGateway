package bank.internalgateway.offers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "deposit_products")
public class DepositProduct {

    @Id
    private String id;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private BigDecimal minAmount;

    @Column(nullable = false)
    private BigDecimal maxAmount;

    @Column(nullable = false)
    private int minTermMonths;

    @Column(nullable = false)
    private int maxTermMonths;

    @Column(nullable = false)
    private BigDecimal baseRate;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DepositProduct() {
    }

    public DepositProduct(
            String id,
            int version,
            String currency,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int minTermMonths,
            int maxTermMonths,
            BigDecimal baseRate,
            boolean active,
            Instant updatedAt) {
        this.id = id;
        this.version = version;
        this.currency = currency;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.minTermMonths = minTermMonths;
        this.maxTermMonths = maxTermMonths;
        this.baseRate = baseRate;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public int getMinTermMonths() {
        return minTermMonths;
    }

    public int getMaxTermMonths() {
        return maxTermMonths;
    }

    public BigDecimal getBaseRate() {
        return baseRate;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
