package bank.internalgateway.processor.kafka;

import bank.internalgateway.processor.config.ProcessorProperties;
import bank.internalgateway.processor.dto.ExternalProcessorOfferMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProcessorEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProcessorEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String schemaVersion;

    public ProcessorEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ProcessorProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = properties.kafka().topic();
        this.schemaVersion = properties.kafka().schemaVersion();
    }

    public void publishExternal(String messageType, ExternalProcessorOfferMessage message) {
        try {
            String messageId = UUID.randomUUID().toString();
            String publishedAt = Instant.now().toString();
            String payload = objectMapper.writeValueAsString(message);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.offerExternalId(), payload);
            record.headers().add("messageId", messageId.getBytes(StandardCharsets.UTF_8));
            record.headers().add("messageType", messageType.getBytes(StandardCharsets.UTF_8));
            record.headers().add("offerExternalId", message.offerExternalId().getBytes(StandardCharsets.UTF_8));
            record.headers().add("schemaVersion", schemaVersion.getBytes(StandardCharsets.UTF_8));
            record.headers().add("publishedAt", publishedAt.getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get();
            log.info("Published external {} messageId={} offerExternalId={}", messageType, messageId, message.offerExternalId());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish processor event", ex);
        }
    }
}
