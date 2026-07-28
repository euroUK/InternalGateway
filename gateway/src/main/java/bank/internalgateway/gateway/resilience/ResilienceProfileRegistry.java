package bank.internalgateway.gateway.resilience;

import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.dsl.DslLoader;
import bank.internalgateway.gateway.dsl.DslMaps;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static bank.internalgateway.gateway.dsl.DslMaps.mapValue;
import static bank.internalgateway.gateway.dsl.DslMaps.parseDuration;
import static bank.internalgateway.gateway.dsl.DslMaps.stringList;
import static bank.internalgateway.gateway.dsl.DslMaps.stringValue;

@Component
public class ResilienceProfileRegistry {

    private static final Pattern RATE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)/second");

    private final DslLoader dslLoader;
    private final long defaultBackoffBaseMs;
    private final long defaultBackoffJitterMs;

    private Map<String, ResilienceProfile> profiles = Map.of();
    private Map<String, RateLimitPolicy> rateLimits = Map.of();

    public ResilienceProfileRegistry(DslLoader dslLoader, GatewayProperties properties) {
        this.dslLoader = dslLoader;
        GatewayProperties.Resilience.Backoff backoff = properties.resilience() != null
                ? properties.resilience().backoff()
                : null;
        this.defaultBackoffBaseMs = backoff != null ? backoff.baseMs() : 100L;
        this.defaultBackoffJitterMs = backoff != null ? backoff.jitterMs() : 50L;
    }

    @PostConstruct
    void loadProfiles() {
        profiles = parseResilienceProfiles();
        rateLimits = parseRateLimits();
    }

    public ResilienceProfile getProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("resilienceProfile is required on fan-out delivery route");
        }
        ResilienceProfile profile = profiles.get(profileName);
        if (profile == null) {
            throw new IllegalStateException("Unknown resilience profile: " + profileName);
        }
        return profile;
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
            Map<String, Object> profileConfig = (Map<String, Object>) profileMap;
            Duration timeout = parseDuration(stringValue(profileConfig.get("timeout")));

            Map<String, Object> retries = mapValue(profileConfig.get("retries"));
            int maxAttempts = retries != null && retries.get("maxAttempts") != null
                    ? Integer.parseInt(retries.get("maxAttempts").toString())
                    : 1;
            List<String> retryOn = stringList(retries != null ? retries.get("retryOn") : null);
            List<String> doNotRetryOn = stringList(retries != null ? retries.get("doNotRetryOn") : null);
            String backoff = retries != null ? stringValue(retries.get("backoff")) : null;

            CircuitBreakerConfig circuitBreaker = parseCircuitBreaker(mapValue(profileConfig.get("circuitBreaker")));
            DeadLetterConfig deadLetter = parseDeadLetter(mapValue(profileConfig.get("deadLetter")));

            result.put(String.valueOf(name), new ResilienceProfile(
                    String.valueOf(name),
                    maxAttempts,
                    retryOn,
                    doNotRetryOn,
                    backoff,
                    timeout,
                    circuitBreaker,
                    deadLetter,
                    defaultBackoffBaseMs,
                    defaultBackoffJitterMs
            ));
        });
        return Map.copyOf(result);
    }

    private CircuitBreakerConfig parseCircuitBreaker(Map<String, Object> circuitBreaker) {
        if (circuitBreaker == null || circuitBreaker.isEmpty()) {
            return null;
        }
        int failureRateThreshold = circuitBreaker.get("failureRateThreshold") != null
                ? Integer.parseInt(circuitBreaker.get("failureRateThreshold").toString())
                : 50;
        int minimumCalls = circuitBreaker.get("minimumCalls") != null
                ? Integer.parseInt(circuitBreaker.get("minimumCalls").toString())
                : 10;
        Duration openDuration = parseDuration(stringValue(circuitBreaker.get("openDuration")));
        return CircuitBreakerConfig.declared(
                failureRateThreshold,
                minimumCalls,
                openDuration != null ? openDuration : Duration.ofSeconds(20)
        );
    }

    private DeadLetterConfig parseDeadLetter(Map<String, Object> deadLetter) {
        if (deadLetter == null || deadLetter.isEmpty()) {
            return null;
        }
        String topicAlias = stringValue(deadLetter.get("topicAlias"));
        boolean retainForReplay = Boolean.parseBoolean(String.valueOf(deadLetter.getOrDefault("retainForReplay", false)));
        return DeadLetterConfig.declared(topicAlias, retainForReplay);
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
}
