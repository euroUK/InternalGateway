package bank.internalgateway.processor.bootstrap;

import bank.internalgateway.processor.dto.ExternalProcessorOfferMessage;
import bank.internalgateway.processor.kafka.ProcessorEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConditionalOnProperty(prefix = "processor.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupSeed implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSeed.class);

    private final ProcessorEventPublisher publisher;

    public StartupSeed(ProcessorEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void run(String... args) {
        log.info("Publishing initial processor offer lifecycle events (external format)");
        publishCreated("proc-offer-001", 1, "TERM-12M", new BigDecimal("12.5"), 12);
        publishCreated("proc-offer-002", 1, "TERM-6M", new BigDecimal("10.5"), 6);
        publishCreated("proc-offer-003", 1, "TERM-24M", new BigDecimal("14.0"), 24);
    }

    private void publishCreated(String offerId, long version, String productCode, BigDecimal interestRatePercent, int termMonths) {
        publisher.publishExternal("OFFER_CREATED", new ExternalProcessorOfferMessage(
                offerId,
                version,
                productCode,
                interestRatePercent,
                termMonths,
                new ExternalProcessorOfferMessage.AmountRange(new BigDecimal("10000"), new BigDecimal("5000000")),
                "RUB"
        ));
    }
}
