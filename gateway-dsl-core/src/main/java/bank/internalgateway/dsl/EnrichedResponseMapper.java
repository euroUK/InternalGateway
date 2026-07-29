package bank.internalgateway.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies DSL responseMapping expressions for enriched ingress routes.
 * Supported sources: request.*, adapter.*, target / target.*, envelope.correlationId
 */
public final class EnrichedResponseMapper {

    private EnrichedResponseMapper() {
    }

    public static Map<String, Object> map(
            Map<String, String> fieldMap,
            Map<String, Object> request,
            Map<String, Object> adapter,
            Map<String, Object> target,
            String correlationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fieldMap == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
            result.put(entry.getKey(), resolve(entry.getValue(), request, adapter, target, correlationId));
        }
        return result;
    }

    private static Object resolve(
            String expression,
            Map<String, Object> request,
            Map<String, Object> adapter,
            Map<String, Object> target,
            String correlationId) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if ("envelope.correlationId".equals(expression)) {
            return correlationId;
        }
        if ("target".equals(expression)) {
            return target;
        }
        if (expression.startsWith("request.")) {
            return lookup(request, expression.substring("request.".length()));
        }
        if (expression.startsWith("adapter.")) {
            return lookup(adapter, expression.substring("adapter.".length()));
        }
        if (expression.startsWith("target.")) {
            return lookup(target, expression.substring("target.".length()));
        }
        throw new IllegalArgumentException("Unsupported responseMapping expression: " + expression);
    }

    private static Object lookup(Map<String, Object> source, String field) {
        if (source == null || field == null || field.isBlank()) {
            return null;
        }
        return source.get(field);
    }
}
