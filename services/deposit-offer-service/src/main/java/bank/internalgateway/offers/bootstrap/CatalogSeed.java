package bank.internalgateway.offers.bootstrap;

import bank.internalgateway.offers.domain.DepositProduct;
import bank.internalgateway.offers.domain.DepositProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class CatalogSeed implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeed.class);

    private final DepositProductRepository productRepository;

    public CatalogSeed(DepositProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }
        log.info("Seeding local deposit product catalog");
        productRepository.save(new DepositProduct(
                "TERM-6M",
                1,
                "RUB",
                new BigDecimal("10000"),
                new BigDecimal("3000000"),
                6,
                6,
                new BigDecimal("0.095"),
                true,
                Instant.now()
        ));
        productRepository.save(new DepositProduct(
                "TERM-12M",
                1,
                "RUB",
                new BigDecimal("10000"),
                new BigDecimal("5000000"),
                12,
                12,
                new BigDecimal("0.110"),
                true,
                Instant.now()
        ));
    }
}
