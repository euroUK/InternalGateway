package bank.internalgateway.offers.test;

import bank.internalgateway.offers.config.OfferServiceProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TestFaultState {

    private final int defaultStatus;
    private final AtomicInteger failRemaining = new AtomicInteger(0);
    private volatile int failStatus;

    public TestFaultState(OfferServiceProperties properties) {
        OfferServiceProperties.Fault fault = properties.fault();
        this.defaultStatus = fault != null ? fault.defaultStatus() : 503;
        this.failStatus = defaultStatus;
    }

    public void configure(int failNextRequests, int statusCode) {
        failRemaining.set(Math.max(0, failNextRequests));
        failStatus = statusCode > 0 ? statusCode : defaultStatus;
    }

    public void reset() {
        failRemaining.set(0);
        failStatus = defaultStatus;
    }

    public boolean shouldFail() {
        return failRemaining.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
    }

    public int failStatus() {
        return failStatus;
    }

    public record FaultSnapshot(int failRemaining, int failStatus) {
    }

    public FaultSnapshot snapshot() {
        return new FaultSnapshot(failRemaining.get(), failStatus);
    }
}
