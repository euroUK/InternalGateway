package bank.internalgateway.gateway.resilience;

import bank.internalgateway.gateway.dsl.DslLoader;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResilienceProfileRegistry {

    private static final Pattern RATE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)/second");

    private final DslLoader dslLoader;

    private Map<String, ResilienceProfile> profiles = Map.of();
    private Map<String, RateLimitPolicy> rateLimits = Map.of();

    public ResilienceProfileRegistry(DslLoader dslLoader) {
        this.dslLoader = dslLoader;
    }

    @PostConstruct
    void loadProfiles() {
        profiles = parseResilienceProfiles();
        rateLimits = parseRateLimits();
    }

    public ResilienceProfile getProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return ResilienceProfile.defaults("default");
        }
        return profiles.getOrDefault(profileName, ResilienceProfile.defaults(profileName));
    }

    public RateLimitPolicy getRateLimit(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return RateLimitPolicy.disabled("none");
        }
        return rateLimits.getOrDefault(profileName, RateLimitPolicy.disabled(profileName));
    }

    public Map<String, ResilienceProfile> allProfiles() {
        return profiles;
    }

    public Map<String, RateLimitPolicy> allRateLimits() {
        return rateLimits;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ResilienceProfile> parseResilienceProfiles() {
        Map<String, ResilienceProfile> result = new LinkedHashMap<>();
        Object profilesObj = dslLoader.messagingModule().get("profiles");
        if (!(profilesObj instanceof Map<?, ?> profilesMap)) {
            return result;
        }
        Object resilienceObj = profilesMap.get("resilience");
        if (!(resilienceObj instanceof Map<?, ?> resilienceMap)) {
            return result;
        }
        resilienceMap.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> profileMap)) {
                return;
            }
            Map<String, Object> retries = mapValue(profileMap.get("retries"));
            int maxAttempts = retries != null && retries.get("maxAttempts") != null
                    ? Integer.parseInt(retries.get("maxAttempts").toString())
                    : 1;
            List<String> retryOn = stringList(retries != null ? retries.get("retryOn") : null);
            List<String> doNotRetryOn = stringList(retries != null ? retries.get("doNotRetryOn") : null);
            String backoff = retries != null ? stringValue(retries.get("backoff")) : null;
            result.put(String.valueOf(name), new ResilienceProfile(
                    String.valueOf(name),
                    maxAttempts,
                    retryOn,
                    doNotRetryOn,
                    backoff
            ));
        });
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, RateLimitPolicy> parseRateLimits() {
        Map<String, RateLimitPolicy> result = new LinkedHashMap<>();
        Object profilesObj = dslLoader.messagingModule().get("profiles");
        if (!(profilesObj instanceof Map<?, ?> profilesMap)) {
            return result;
        }
        Object rateLimitsObj = profilesMap.get("rateLimits");
        if (!(rateLimitsObj instanceof Map<?, ?> rateLimitsMap)) {
            return result;
        }
        rateLimitsMap.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> limitMap)) {
                return;
            }
            double sustained = parseRate(stringValue(limitMap.get("sustained")));
            int burst = limitMap.get("burst") != null
                    ? Integer.parseInt(limitMap.get("burst").toString())
                    : (int) Math.max(1, sustained);
            result.put(String.valueOf(name), new RateLimitPolicy(String.valueOf(name), sustained, burst));
        });
        return Map.copyOf(result);
    }

    private double parseRate(String sustained) {
        if (sustained == null) {
            return Double.MAX_VALUE;
        }
        Matcher matcher = RATE_PATTERN.matcher(sustained.trim());
        if (!matcher.matches()) {
            return Double.MAX_VALUE;
        }
        return Double.parseDouble(matcher.group(1));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }
}
