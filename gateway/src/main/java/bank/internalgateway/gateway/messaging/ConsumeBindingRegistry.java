package bank.internalgateway.gateway.messaging;

import bank.internalgateway.gateway.dsl.DslLoader;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConsumeBindingRegistry {

    private static final Pattern TTL_PATTERN = Pattern.compile("(\\d+)([dhms])");

    private final DslLoader dslLoader;

    private Map<String, ConsumeBinding> bindingsById = Map.of();
    private Map<String, ConsumeBinding> bindingsByMappingFile = Map.of();

    public ConsumeBindingRegistry(DslLoader dslLoader) {
        this.dslLoader = dslLoader;
    }

    @PostConstruct
    void loadBindings() {
        Map<String, ConsumeBinding> byId = new LinkedHashMap<>();
        Map<String, ConsumeBinding> byMappingFile = new LinkedHashMap<>();
        Map<String, String> physicalTopics = physicalTopicsByAlias();

        Object messaging = dslLoader.messagingModule().get("messaging");
        if (!(messaging instanceof Map<?, ?> messagingMap)) {
            bindingsById = Map.of();
            bindingsByMappingFile = Map.of();
            return;
        }

        Object consumeBindingsObj = messagingMap.get("consumeBindings");
        if (!(consumeBindingsObj instanceof List<?> consumeBindings)) {
            bindingsById = Map.of();
            bindingsByMappingFile = Map.of();
            return;
        }

        for (Object item : consumeBindings) {
            if (!(item instanceof Map<?, ?> bindingMap)) {
                continue;
            }
            ConsumeBinding binding = parseBinding(bindingMap, physicalTopics);
            if (binding == null) {
                continue;
            }
            byId.put(binding.bindingId(), binding);
            if (binding.mappingFile() != null && !binding.mappingFile().isBlank()) {
                byMappingFile.put(binding.mappingFile(), binding);
            }
        }

        bindingsById = Map.copyOf(byId);
        bindingsByMappingFile = Map.copyOf(byMappingFile);
    }

    public Optional<ConsumeBinding> findById(String bindingId) {
        return Optional.ofNullable(bindingsById.get(bindingId));
    }

    public Optional<ConsumeBinding> findByMappingFile(String mappingFile) {
        return Optional.ofNullable(bindingsByMappingFile.get(mappingFile));
    }

    public List<ConsumeBinding> allBindings() {
        return List.copyOf(bindingsById.values());
    }

    public List<FanOutRoute> resolveRoutes(String bindingId, String eventType) {
        ConsumeBinding binding = bindingsById.get(bindingId);
        if (binding == null) {
            return List.of();
        }

        List<FanOutRoute> routes = new ArrayList<>();
        for (FanOutTarget target : binding.fanOutTargets()) {
            if (!target.matchesEventType(eventType)) {
                continue;
            }
            String path = target.resolvePath(eventType);
            if (path == null || path.isBlank()) {
                continue;
            }
            routes.add(new FanOutRoute(
                    binding.bindingId(),
                    target.targetId(),
                    target.service(),
                    target.method(),
                    path,
                    target.envelopeClaims(),
                    target.resilienceProfile(),
                    target.rateLimitProfile()
            ));
        }
        return List.copyOf(routes);
    }

    public List<ResolvedFanOutRouteView> allFanOutRoutes() {
        List<ResolvedFanOutRouteView> views = new ArrayList<>();
        for (ConsumeBinding binding : bindingsById.values()) {
            for (FanOutTarget target : binding.fanOutTargets()) {
                if (!target.pathByEventType().isEmpty()) {
                    target.pathByEventType().forEach((eventType, path) -> views.add(
                            new ResolvedFanOutRouteView(
                                    binding.bindingId(),
                                    target.targetId(),
                                    eventType,
                                    target.service(),
                                    path
                            )
                    ));
                } else if (target.defaultPath() != null) {
                    for (String eventType : target.allowedEventTypes()) {
                        views.add(new ResolvedFanOutRouteView(
                                binding.bindingId(),
                                target.targetId(),
                                eventType,
                                target.service(),
                                target.defaultPath()
                        ));
                    }
                }
            }
        }
        return List.copyOf(views);
    }

    public String kafkaTopic(String bindingId) {
        return findById(bindingId)
                .map(ConsumeBinding::physicalTopic)
                .filter(topic -> topic != null && !topic.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "No physical Kafka topic configured for binding '" + bindingId + "'"));
    }

    public String kafkaConsumerGroup(String bindingId) {
        return findById(bindingId)
                .map(ConsumeBinding::consumerGroup)
                .filter(group -> group != null && !group.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "No consumer group configured for binding '" + bindingId + "'"));
    }

    @SuppressWarnings("unchecked")
    private ConsumeBinding parseBinding(Map<?, ?> bindingMap, Map<String, String> physicalTopics) {
        String bindingId = stringValue(bindingMap.get("id"));
        if (bindingId == null) {
            return null;
        }

        Map<String, Object> source = mapValue(bindingMap.get("source"));
        String topicAlias = source != null ? stringValue(source.get("topicAlias")) : null;
        String consumerGroup = source != null ? stringValue(source.get("consumerGroup")) : null;
        String providerSet = stringValue(bindingMap.get("providerSet"));
        String physicalTopic = topicAlias != null ? physicalTopics.get(topicAlias) : null;

        Map<String, Object> normalization = mapValue(bindingMap.get("normalization"));
        String mappingFile = normalization != null ? stringValue(normalization.get("mappingFile")) : null;

        Map<String, Object> deduplication = mapValue(bindingMap.get("deduplication"));
        String dedupKey = deduplication != null ? stringValue(deduplication.get("key")) : "header://eventId";
        Duration dedupTtl = deduplication != null
                ? parseTtl(stringValue(deduplication.get("ttl")))
                : Duration.ofDays(7);

        List<FanOutTarget> targets = parseFanOutTargets(mapValue(bindingMap.get("fanOut")));

        return new ConsumeBinding(
                bindingId,
                topicAlias,
                physicalTopic,
                consumerGroup,
                providerSet,
                mappingFile,
                dedupKey,
                dedupTtl,
                targets
        );
    }

    @SuppressWarnings("unchecked")
    private List<FanOutTarget> parseFanOutTargets(Map<String, Object> fanOut) {
        if (fanOut == null) {
            return List.of();
        }
        Object targetsObj = fanOut.get("targets");
        if (!(targetsObj instanceof List<?> targets)) {
            return List.of();
        }

        List<FanOutTarget> result = new ArrayList<>();
        for (Object item : targets) {
            if (!(item instanceof Map<?, ?> targetMap)) {
                continue;
            }
            String targetId = stringValue(targetMap.get("id"));
            Map<String, Object> filter = mapValue(targetMap.get("filter"));
            Set<String> allowedEventTypes = parseAllowedEventTypes(filter);

            Map<String, Object> delivery = mapValue(targetMap.get("delivery"));
            if (delivery == null) {
                continue;
            }

            String service = stringValue(delivery.get("service"));
            String method = stringValue(delivery.get("method"));
            if (method == null) {
                method = "POST";
            }

            Map<String, String> pathByEventType = stringMap(delivery.get("pathByEventType"));
            String defaultPath = stringValue(delivery.get("path"));

            List<String> envelopeClaims = parseEnvelopeClaims(delivery);
            String resilienceProfile = stringValue(delivery.get("resilienceProfile"));
            String rateLimitProfile = stringValue(delivery.get("rateLimitProfile"));
            if (rateLimitProfile == null) {
                rateLimitProfile = stringValue(targetMap.get("rateLimitProfile"));
            }

            result.add(new FanOutTarget(
                    targetId,
                    allowedEventTypes,
                    service,
                    method,
                    pathByEventType,
                    defaultPath,
                    envelopeClaims,
                    resilienceProfile,
                    rateLimitProfile
            ));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Set<String> parseAllowedEventTypes(Map<String, Object> filter) {
        Set<String> types = new LinkedHashSet<>();
        if (filter == null) {
            return types;
        }
        Object single = filter.get("eventType");
        if (single != null) {
            types.add(single.toString());
        }
        Object any = filter.get("eventTypeAny");
        if (any instanceof List<?> list) {
            list.forEach(item -> types.add(String.valueOf(item)));
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseEnvelopeClaims(Map<String, Object> delivery) {
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

    @SuppressWarnings("unchecked")
    private Map<String, String> physicalTopicsByAlias() {
        Map<String, String> result = new LinkedHashMap<>();
        Object providerSetsObj = dslLoader.messagingModule().get("providerSets");
        if (!(providerSetsObj instanceof Map<?, ?> providerSets)) {
            return result;
        }

        for (Object providerSetValue : providerSets.values()) {
            if (!(providerSetValue instanceof Map<?, ?> providerSetMap)) {
                continue;
            }
            Object providersObj = providerSetMap.get("providers");
            if (!(providersObj instanceof Map<?, ?> providers)) {
                continue;
            }
            for (Object providerValue : providers.values()) {
                if (!(providerValue instanceof Map<?, ?> provider)) {
                    continue;
                }
                Object topicsObj = provider.get("topics");
                if (!(topicsObj instanceof Map<?, ?> topics)) {
                    continue;
                }
                topics.forEach((alias, topicConfig) -> {
                    if (topicConfig instanceof Map<?, ?> topicMap) {
                        String physicalTopic = stringValue(topicMap.get("physicalTopic"));
                        if (physicalTopic != null) {
                            result.put(String.valueOf(alias), physicalTopic);
                        }
                    }
                });
            }
        }
        return result;
    }

    static Duration parseTtl(String ttl) {
        if (ttl == null || ttl.isBlank()) {
            return Duration.ofDays(7);
        }
        Matcher matcher = TTL_PATTERN.matcher(ttl.trim());
        if (!matcher.matches()) {
            return Duration.ofDays(7);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "d" -> Duration.ofDays(amount);
            case "h" -> Duration.ofHours(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "s" -> Duration.ofSeconds(amount);
            default -> Duration.ofDays(7);
        };
    }

    static String dedupFieldFromKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return "eventId";
        }
        if (dedupKey.startsWith("header://")) {
            return dedupKey.substring("header://".length());
        }
        return dedupKey;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v != null ? v.toString() : null));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    public record ConsumeBinding(
            String bindingId,
            String topicAlias,
            String physicalTopic,
            String consumerGroup,
            String providerSet,
            String mappingFile,
            String dedupKey,
            Duration dedupTtl,
            List<FanOutTarget> fanOutTargets
    ) {
        public String inboundKafkaPath() {
            return physicalTopic != null ? "kafka://" + physicalTopic : "kafka://unknown";
        }
    }

    public record FanOutTarget(
            String targetId,
            Set<String> allowedEventTypes,
            String service,
            String method,
            Map<String, String> pathByEventType,
            String defaultPath,
            List<String> envelopeClaims,
            String resilienceProfile,
            String rateLimitProfile
    ) {
        boolean matchesEventType(String eventType) {
            if (allowedEventTypes.isEmpty()) {
                return true;
            }
            return allowedEventTypes.contains(eventType);
        }

        String resolvePath(String eventType) {
            if (pathByEventType.containsKey(eventType)) {
                return pathByEventType.get(eventType);
            }
            return defaultPath;
        }
    }

    public record FanOutRoute(
            String bindingId,
            String targetId,
            String service,
            String method,
            String path,
            List<String> envelopeClaims,
            String resilienceProfile,
            String rateLimitProfile
    ) {
    }

    public record ResolvedFanOutRouteView(
            String bindingId,
            String targetId,
            String eventType,
            String service,
            String path
    ) {
    }
}
