package bank.internalgateway.gateway.messaging;

import java.util.List;
import java.util.Map;

public final class EventMappingModels {

    private EventMappingModels() {
    }

    public record FieldMappingView(
            String sourceField,
            String targetField,
            String transform
    ) {
    }

    public record MappingConfigView(
            String sourceSystem,
            String description,
            Map<String, String> headerMapping,
            Map<String, String> eventTypeMapping,
            Map<String, String> bodyFieldMapping,
            List<FieldMappingView> bodyMappings
    ) {
    }

    public record TransformRule(
            String rule,
            String from,
            String description
    ) {
    }

    public record LegacyDetection(
            List<String> headersPresent,
            List<String> headersAbsent
    ) {
        boolean matches(Map<String, String> headers) {
            if (headersPresent != null) {
                for (String header : headersPresent) {
                    if (!headers.containsKey(header)) {
                        return false;
                    }
                }
            }
            if (headersAbsent != null) {
                for (String header : headersAbsent) {
                    if (headers.containsKey(header)) {
                        return false;
                    }
                }
            }
            return headersPresent != null && !headersPresent.isEmpty()
                    || headersAbsent != null && !headersAbsent.isEmpty();
        }
    }
}
