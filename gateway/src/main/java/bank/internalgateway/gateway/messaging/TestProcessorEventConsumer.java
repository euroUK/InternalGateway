package bank.internalgateway.gateway.messaging;

import bank.internalgateway.gateway.config.GatewayProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TestProcessorEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TestProcessorEventConsumer.class);
    private static final String TEST_BINDING_ID = "test-processor-offer-lifecycle";

    private final EventFanOutService eventFanOutService;
    private final ConfigurableEventMapper eventMapper;
    private final ConsumeBindingRegistry consumeBindingRegistry;

    public TestProcessorEventConsumer(
            EventFanOutService eventFanOutService,
            ConfigurableEventMapper eventMapper,
            ConsumeBindingRegistry consumeBindingRegistry) {
        this.eventFanOutService = eventFanOutService;
        this.eventMapper = eventMapper;
        this.consumeBindingRegistry = consumeBindingRegistry;
    }

    @KafkaListener(
            topics = "#{@consumeBindingRegistry.kafkaTopic('test-processor-offer-lifecycle')}",
            groupId = "#{@consumeBindingRegistry.kafkaConsumerGroup('test-processor-offer-lifecycle')}"
    )
    public void onTestProcessorEvent(ConsumerRecord<String, String> record) throws Exception {
        ConsumeBindingRegistry.ConsumeBinding binding = consumeBindingRegistry.findById(TEST_BINDING_ID)
                .orElseThrow(() -> new IllegalStateException("Test binding not found: " + TEST_BINDING_ID));

        Map<String, String> headers = readHeaders(record);
        CanonicalInboundEvent canonical = eventMapper.map(binding.mappingFile(), headers, record.value());

        if (canonical.eventId() == null || canonical.eventType() == null) {
            log.warn("Skipping test processor record with missing mapped identity: offset={}", record.offset());
            return;
        }

        eventFanOutService.deliver(TEST_BINDING_ID, canonical);
    }

    private Map<String, String> readHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }
}
