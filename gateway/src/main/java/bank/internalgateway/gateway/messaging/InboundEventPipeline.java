package bank.internalgateway.gateway.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InboundEventPipeline {

    private static final Logger log = LoggerFactory.getLogger(InboundEventPipeline.class);

    private final EventFanOutService eventFanOutService;
    private final ConfigurableEventMapper eventMapper;
    private final ConsumeBindingRegistry consumeBindingRegistry;

    public InboundEventPipeline(
            EventFanOutService eventFanOutService,
            ConfigurableEventMapper eventMapper,
            ConsumeBindingRegistry consumeBindingRegistry) {
        this.eventFanOutService = eventFanOutService;
        this.eventMapper = eventMapper;
        this.consumeBindingRegistry = consumeBindingRegistry;
    }

    public void process(String bindingId, ConsumerRecord<String, String> record) throws Exception {
        ConsumeBindingRegistry.ConsumeBinding binding = consumeBindingRegistry.findById(bindingId)
                .orElseThrow(() -> new IllegalStateException("Kafka binding not found: " + bindingId));

        String mappingFile = binding.mappingFile();
        if (mappingFile == null || mappingFile.isBlank()) {
            throw new IllegalStateException("Binding '" + bindingId + "' has no normalization.mappingFile");
        }

        Map<String, String> headers = readHeaders(record);
        CanonicalInboundEvent canonical = eventMapper.map(mappingFile, headers, record.value());

        if (canonical.eventId() == null || canonical.eventType() == null) {
            log.warn("Skipping Kafka record with missing mapped identity: binding={} offset={}", bindingId, record.offset());
            return;
        }

        try {
            eventFanOutService.deliver(bindingId, canonical);
        } catch (Exception ex) {
            log.error("Failed to fan-out event binding={} eventId={}", bindingId, canonical.eventId(), ex);
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
