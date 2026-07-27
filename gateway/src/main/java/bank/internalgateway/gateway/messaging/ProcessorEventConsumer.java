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
public class ProcessorEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProcessorEventConsumer.class);

    private final EventFanOutService eventFanOutService;
    private final ConfigurableEventMapper eventMapper;
    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final String activeBindingId;

    public ProcessorEventConsumer(
            EventFanOutService eventFanOutService,
            ConfigurableEventMapper eventMapper,
            ConsumeBindingRegistry consumeBindingRegistry,
            GatewayProperties properties) {
        this.eventFanOutService = eventFanOutService;
        this.eventMapper = eventMapper;
        this.consumeBindingRegistry = consumeBindingRegistry;
        this.activeBindingId = properties.kafka() != null && properties.kafka().activeBindingId() != null
                ? properties.kafka().activeBindingId()
                : "deposit-processor-offer-lifecycle";
    }

    @KafkaListener(
            topics = "#{@consumeBindingRegistry.kafkaTopic('${gateway.kafka.active-binding-id}')}",
            groupId = "#{@consumeBindingRegistry.kafkaConsumerGroup('${gateway.kafka.active-binding-id}')}"
    )
    public void onProcessorEvent(ConsumerRecord<String, String> record) throws Exception {
        ConsumeBindingRegistry.ConsumeBinding binding = consumeBindingRegistry.findById(activeBindingId)
                .orElseThrow(() -> new IllegalStateException("Active Kafka binding not found: " + activeBindingId));

        String mappingFile = binding.mappingFile();
        if (mappingFile == null || mappingFile.isBlank()) {
            throw new IllegalStateException("Binding '" + activeBindingId + "' has no normalization.mappingFile");
        }

        Map<String, String> headers = readHeaders(record);
        CanonicalInboundEvent canonical = eventMapper.map(mappingFile, headers, record.value());

        if (canonical.eventId() == null || canonical.eventType() == null) {
            log.warn("Skipping Kafka record with missing mapped identity: offset={}", record.offset());
            return;
        }

        try {
            eventFanOutService.deliver(activeBindingId, canonical);
        } catch (Exception ex) {
            log.error("Failed to fan-out processor event eventId={}", canonical.eventId(), ex);
            throw ex;
        }
    }

    private Map<String, String> readHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }
}
