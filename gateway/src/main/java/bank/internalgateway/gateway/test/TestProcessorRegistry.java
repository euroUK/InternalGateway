package bank.internalgateway.gateway.test;

import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TestProcessorRegistry {

    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final CopyOnWriteArrayList<TestProcessorRegistration> registrations = new CopyOnWriteArrayList<>();

    public TestProcessorRegistry(ConsumeBindingRegistry consumeBindingRegistry) {
        this.consumeBindingRegistry = consumeBindingRegistry;
    }

    @PostConstruct
    void registerBuiltInTestProcessor() {
        register(new TestProcessorRegistration(
                "test-processor-1",
                "test-processor-offer-lifecycle",
                "test-processor-event-mapping.yaml",
                "test.processor.offer.lifecycle.v1",
                "http://test-processor:8092",
                "Registered from DSL binding test-processor-offer-lifecycle"
        ));
    }

    public TestProcessorRegistration register(TestProcessorRegistration registration) {
        consumeBindingRegistry.findById(registration.bindingId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown bindingId for test processor: " + registration.bindingId()));
        registrations.removeIf(item -> item.processorId().equals(registration.processorId()));
        registrations.add(registration.withRegisteredAt(Instant.now()));
        return registration;
    }

    public List<TestProcessorRegistration> all() {
        return List.copyOf(registrations);
    }

    public record TestProcessorRegistration(
            String processorId,
            String bindingId,
            String mappingFile,
            String physicalTopic,
            String demoBaseUrl,
            String note,
            Instant registeredAt
    ) {
        TestProcessorRegistration(
                String processorId,
                String bindingId,
                String mappingFile,
                String physicalTopic,
                String demoBaseUrl,
                String note) {
            this(processorId, bindingId, mappingFile, physicalTopic, demoBaseUrl, note, Instant.now());
        }

        TestProcessorRegistration withRegisteredAt(Instant registeredAt) {
            return new TestProcessorRegistration(
                    processorId, bindingId, mappingFile, physicalTopic, demoBaseUrl, note, registeredAt);
        }
    }
}
