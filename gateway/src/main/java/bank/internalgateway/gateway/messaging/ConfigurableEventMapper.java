package bank.internalgateway.gateway.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConfigurableEventMapper {

    private final EventMappingRegistry mappingRegistry;
    private final MappingTransformApplicator transformApplicator;
    private final ObjectMapper objectMapper;

    public ConfigurableEventMapper(
            EventMappingRegistry mappingRegistry,
            MappingTransformApplicator transformApplicator,
            ObjectMapper objectMapper) {
        this.mappingRegistry = mappingRegistry;
        this.transformApplicator = transformApplicator;
        this.objectMapper = objectMapper;
    }

    public CanonicalInboundEvent map(
            String mappingConfigFile,
            Map<String, String> sourceHeaders,
            String sourcePayloadJson) throws IOException {

        EventMappingRegistry.ParsedEventMapping mapping = mappingRegistry.getMapping(mappingConfigFile);
        if (mapping == null) {
            return mapPassThrough(sourceHeaders, sourcePayloadJson, "mapping file not found: " + mappingConfigFile);
        }

        if (mapping.legacyDetection() != null && mapping.legacyDetection().matches(sourceHeaders)) {
            return mapPassThrough(sourceHeaders, sourcePayloadJson, "legacy pass-through");
        }

        JsonNode body = objectMapper.readTree(sourcePayloadJson);
        Map<String, String> canonicalHeaders = mapHeaders(sourceHeaders, mapping.headerMapping());

        String sourceMessageType = sourceHeaderForTarget(sourceHeaders, mapping.headerMapping(), "eventType");
        if (sourceMessageType == null) {
            sourceMessageType = firstNonNull(
                    sourceHeaders.get("messageType"),
                    sourceHeaders.get("eventType")
            );
        }

        String canonicalEventType = mapping.eventTypeMapping()
                .getOrDefault(sourceMessageType, sourceMessageType);
        if (canonicalEventType != null) {
            canonicalHeaders.put("eventType", canonicalEventType);
        }

        Map<String, Object> payload = mapBodyFields(body, mapping);
        for (Map.Entry<String, String> headerEntry : canonicalHeaders.entrySet()) {
            payload.putIfAbsent(headerEntry.getKey(), headerEntry.getValue());
        }

        String eventId = stringValue(canonicalHeaders.get("eventId"));
        String eventType = stringValue(canonicalHeaders.get("eventType"));
        String occurredAt = stringValue(canonicalHeaders.get("occurredAt"));
        if (occurredAt == null) {
            occurredAt = Instant.now().toString();
            payload.putIfAbsent("occurredAt", occurredAt);
        }

        return new CanonicalInboundEvent(
                eventId,
                eventType,
                occurredAt,
                payload,
                sourceMessageType,
                buildSummary(sourceMessageType, canonicalEventType, mapping)
        );
    }

    public EventMappingModels.MappingConfigView configView(String mappingConfigFile) {
        EventMappingRegistry.ParsedEventMapping mapping = mappingRegistry.getMapping(mappingConfigFile);
        if (mapping == null) {
            return new EventMappingModels.MappingConfigView(
                    "",
                    "",
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of()
            );
        }
        return mapping.toConfigView();
    }

    private Map<String, String> mapHeaders(
            Map<String, String> sourceHeaders,
            Map<String, String> headerMapping) {
        Map<String, String> canonicalHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headerMapping.entrySet()) {
            String sourceValue = sourceHeaders.get(entry.getKey());
            if (sourceValue != null) {
                canonicalHeaders.put(entry.getValue(), sourceValue);
            }
        }
        return canonicalHeaders;
    }

    private Map<String, Object> mapBodyFields(
            JsonNode body,
            EventMappingRegistry.ParsedEventMapping mapping) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.bodyFieldMapping().entrySet()) {
            Object rawValue = readValue(body, entry.getKey());
            EventMappingModels.TransformRule transform = mapping.transforms().get(entry.getValue());
            Object transformed = transformApplicator.apply(rawValue, transform);
            if (transformed != null) {
                payload.put(entry.getValue(), transformed);
            }
        }
        return payload;
    }

    private CanonicalInboundEvent mapPassThrough(
            Map<String, String> headers,
            String payloadJson,
            String reason) throws IOException {
        JsonNode body = objectMapper.readTree(payloadJson);
        Map<String, Object> payload = objectMapper.convertValue(body, Map.class);

        String eventId = firstNonNull(headers.get("eventId"), headers.get("messageId"));
        String eventType = firstNonNull(headers.get("eventType"), headers.get("messageType"));
        String occurredAt = firstNonNull(
                headers.get("occurredAt"),
                headers.get("publishedAt"),
                Instant.now().toString()
        );

        payload.putIfAbsent("eventId", eventId);
        payload.putIfAbsent("eventType", eventType);
        payload.putIfAbsent("occurredAt", occurredAt);

        return new CanonicalInboundEvent(
                eventId,
                eventType,
                occurredAt,
                payload,
                eventType,
                reason
        );
    }

    private String buildSummary(
            String sourceMessageType,
            String canonicalEventType,
            EventMappingRegistry.ParsedEventMapping mapping) {
        List<String> parts = new ArrayList<>();
        if (sourceMessageType != null && canonicalEventType != null) {
            parts.add(sourceMessageType + " → " + canonicalEventType);
        }
        mapping.bodyFieldMapping().forEach((source, target) -> {
            EventMappingModels.TransformRule transform = mapping.transforms().get(target);
            if (transform != null && transform.rule() != null && !transform.rule().isBlank()) {
                parts.add(source + " → " + target + " (" + transform.rule() + ")");
            } else {
                parts.add(source + " → " + target);
            }
        });
        return String.join("; ", parts);
    }

    private String sourceHeaderForTarget(
            Map<String, String> sourceHeaders,
            Map<String, String> headerMapping,
            String targetField) {
        for (Map.Entry<String, String> entry : headerMapping.entrySet()) {
            if (targetField.equals(entry.getValue())) {
                return sourceHeaders.get(entry.getKey());
            }
        }
        return null;
    }

    private Object readValue(JsonNode body, String path) {
        JsonNode node = readPath(body, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private JsonNode readPath(JsonNode body, String path) {
        if (!path.contains(".")) {
            return body.get(path);
        }
        JsonNode current = body;
        for (String part : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private String stringValue(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    @SafeVarargs
    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
