package bank.internalgateway.gateway.dsl;

import java.time.Duration;
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

    @SuppressWarnings("unchecked")
    public static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v != null ? v.toString() : null));
        return result;
    }

    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> durationFromMillis(amount);
            case "s" -> durationFromSeconds(amount);
            case "m" -> durationFromMinutes(amount);
            case "h" -> durationFromHours(amount);
            case "d" -> durationFromDays(amount);
            default -> null;
        };
    }

    private static Duration durationFromMillis(double millis) {
        long wholeMillis = (long) millis;
        long nanos = Math.round((millis - wholeMillis) * 1_000_000L);
        return Duration.ofMillis(wholeMillis).plusNanos(nanos);
    }

    private static Duration durationFromSeconds(double seconds) {
        long wholeSeconds = (long) seconds;
        long nanos = Math.round((seconds - wholeSeconds) * 1_000_000_000L);
        return Duration.ofSeconds(wholeSeconds, nanos);
    }

    private static Duration durationFromMinutes(double minutes) {
        return durationFromSeconds(minutes * 60);
    }

    private static Duration durationFromHours(double hours) {
        return durationFromSeconds(hours * 3600);
    }

    private static Duration durationFromDays(double days) {
        return durationFromSeconds(days * 86_400);
    }

    public static List<String> parseEnvelopeClaims(Map<String, Object> delivery) {
        Map<String, Object> identityEnvelope = mapValue(delivery.get("identityEnvelope"));
        if (identityEnvelope == null) {
            return List.of();
        }
        Object claimsObj = identityEnvelope.get("claims");
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
