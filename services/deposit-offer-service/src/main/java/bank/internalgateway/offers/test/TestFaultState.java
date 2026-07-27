package bank.internalgateway.offers.test;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TestFaultState {

    private final AtomicInteger failRemaining = new AtomicInteger(0);
    private volatile int failStatus = 503;

    public void configure(int count, int statusCode) {
        failRemaining.set(Math.max(0, count));
        failStatus = statusCode > 0 ? statusCode : 503;
    }

    public void reset() {
        failRemaining.set(0);
    }

    public FaultSnapshot snapshot() {
        return new FaultSnapshot(failRemaining.get(), failStatus);
    }

    public boolean shouldFail() {
        return failRemaining.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
    }

    public int failStatus() {
        return failStatus;
    }

    public record FaultSnapshot(int failRemaining, int failStatus) {
    }
}
