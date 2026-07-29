package bank.internalgateway.dsl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StaticCapabilityRenderer {

    private StaticCapabilityRenderer() {
    }

    public static Map<String, Object> render(
            CompiledBenchmarkModule.CompiledCapabilityRoute capability,
            String requestPath) {
        Map<String, String> pathVars = PathTemplate.extract(capability.pathTemplate(), requestPath);
        if (pathVars == null) {
            throw new IllegalArgumentException("Path does not match capability template: " + requestPath);
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        capability.responseTemplate().forEach((key, value) ->
                rendered.put(key, substitute(value, pathVars)));
        return rendered;
    }

    private static Object substitute(Object value, Map<String, String> pathVars) {
        if (!(value instanceof String text)) {
            return value;
        }
        if ("{now}".equals(text)) {
            return Instant.now().toString();
        }
        String result = text;
        for (Map.Entry<String, String> entry : pathVars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (result.matches("-?\\d+(\\.\\d+)?")) {
            if (result.contains(".")) {
                return new BigDecimal(result);
            }
            try {
                return Long.parseLong(result);
            } catch (NumberFormatException ignored) {
                return result;
            }
        }
        return result;
    }
}
