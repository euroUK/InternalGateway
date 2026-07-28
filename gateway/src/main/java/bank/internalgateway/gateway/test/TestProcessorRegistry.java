package bank.internalgateway.gateway.test;

import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.messaging.ConsumeBindingRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TestProcessorRegistry {

    private static final String TEST_PROCESSOR_SERVICE = "test-processor";
    private static final String DEFAULT_TEST_BINDING = "test-processor-offer-lifecycle";

    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final ServiceUrlResolver serviceUrlResolver;
    private final GatewayProperties properties;
    private final CopyOnWriteArrayList<TestProcessorRegistration> registrations = new CopyOnWriteArrayList<>();

    public TestProcessorRegistry(
            ConsumeBindingRegistry consumeBindingRegistry,
            ServiceUrlResolver serviceUrlResolver,
            GatewayProperties properties) {
        this.consumeBindingRegistry = consumeBindingRegistry;
        this.serviceUrlResolver = serviceUrlResolver;
        this.properties = properties;
    }

    @PostConstruct
    void registerBuiltInTestProcessor() {
        String bindingId = properties.testHarness() != null && properties.testHarness().defaultBindingId() != null
                ? properties.testHarness().defaultBindingId()
                : DEFAULT_TEST_BINDING;
        ConsumeBindingRegistry.ConsumeBinding binding = consumeBindingRegistry.findById(bindingId)
                .orElseThrow(() -> new IllegalStateException("Test harness binding not found in DSL: " + bindingId));

        register(new TestProcessorRegistration(
                "test-processor-1",
                binding.bindingId(),
                binding.mappingFile(),
                binding.physicalTopic(),
                serviceUrlResolver.resolve(TEST_PROCESSOR_SERVICE),
                "Registered from DSL binding " + binding.bindingId()
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
