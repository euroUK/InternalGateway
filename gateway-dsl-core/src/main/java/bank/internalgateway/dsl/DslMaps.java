package bank.internalgateway.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DslMaps {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)");

    private DslMaps() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    public static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    public static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    public static Integer intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            Matcher matcher = DURATION_PATTERN.matcher(text.trim());
            if (matcher.matches() && "s".equals(matcher.group(2))) {
                return (int) Double.parseDouble(matcher.group(1));
            }
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static Map<String, Object> objectMap(Object value) {
        Map<String, Object> source = mapValue(value);
        if (source == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(source);
    }

    public static List<String> parseEnvelopeClaims(Map<String, Object> identity) {
        Map<String, Object> forwarded = mapValue(identity != null ? identity.get("forwardedEnvelope") : null);
        if (forwarded == null) {
            return List.of();
        }
        Object claimsObj = forwarded.get("claims");
        if (!(claimsObj instanceof List<?> claims)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object claim : claims) {
            if (claim != null) {
                result.add(claim.toString());
            }
        }
        return List.copyOf(result);
    }
}
