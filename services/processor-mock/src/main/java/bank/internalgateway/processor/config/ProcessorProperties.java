package bank.internalgateway.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "processor")
public record ProcessorProperties(
        Seed seed,
        Cors cors,
        Kafka kafka
) {
    public record Seed(boolean enabled) {
    }

    public record Cors(String allowedOrigins) {
    }

    public record Kafka(String topic, String schemaVersion) {
    }
}
