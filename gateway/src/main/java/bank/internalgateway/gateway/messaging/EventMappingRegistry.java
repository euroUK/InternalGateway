package bank.internalgateway.gateway.messaging;

import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.dsl.DslMaps;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@DependsOn("consumeBindingRegistry")
public class EventMappingRegistry {

    private final GatewayProperties properties;
    private final ConsumeBindingRegistry consumeBindingRegistry;

    private volatile List<RegisteredEventMappingView> registeredMappings = List.of();
    private volatile Map<String, ParsedEventMapping> mappingsByConfigFile = Map.of();

    public EventMappingRegistry(GatewayProperties properties, ConsumeBindingRegistry consumeBindingRegistry) {
        this.properties = properties;
        this.consumeBindingRegistry = consumeBindingRegistry;
    }

    @PostConstruct
    void loadAll() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        Path dslPath = Path.of(properties.dslPath());

        List<RegisteredEventMappingView> views = new ArrayList<>();
        Map<String, ParsedEventMapping> parsedByFile = new LinkedHashMap<>();

        if (Files.isDirectory(dslPath)) {
            try (Stream<Path> files = Files.list(dslPath)) {
                List<Path> mappingFiles = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-event-mapping.yaml"))
                        .sorted()
                        .toList();

                for (Path mappingFile : mappingFiles) {
                    String configFile = mappingFile.getFileName().toString();
                    ParsedEventMapping parsed = parseMappingFile(mappingFile);
                    if (parsed == null) {
                        continue;
                    }
                    parsedByFile.put(configFile, parsed);

                    ConsumeBindingRegistry.ConsumeBinding binding =
                            consumeBindingRegistry.findByMappingFile(configFile).orElse(null);
                    views.add(new RegisteredEventMappingView(
                            mappingId(configFile),
                            configFile,
                            parsed.sourceSystem(),
                            parsed.description(),
                            binding != null ? binding.bindingId() : null,
                            binding != null ? binding.topicAlias() : null,
                            binding != null ? binding.consumerGroup() : null,
                            parsed.headerMapping(),
                            parsed.eventTypeMapping(),
                            parsed.bodyMappings()
                    ));
                }
            }
        }

        registeredMappings = List.copyOf(views);
        mappingsByConfigFile = Map.copyOf(parsedByFile);
    }

    public List<RegisteredEventMappingView> registeredMappings() {
        return registeredMappings;
    }

    public ParsedEventMapping getMapping(String configFile) {
        return mappingsByConfigFile.get(configFile);
    }

    public EventMappingModels.MappingConfigView configViewForBinding(String bindingId) {
        return consumeBindingRegistry.findById(bindingId)
                .map(ConsumeBindingRegistry.ConsumeBinding::mappingFile)
                .map(this::configViewForFile)
                .orElse(emptyConfigView());
    }

    public EventMappingModels.MappingConfigView configViewForFile(String configFile) {
        ParsedEventMapping mapping = getMapping(configFile);
        return mapping != null ? mapping.toConfigView() : emptyConfigView();
    }

    private EventMappingModels.MappingConfigView emptyConfigView() {
        return new EventMappingModels.MappingConfigView("", "", Map.of(), Map.of(), Map.of(), List.of());
    }

    private ParsedEventMapping parseMappingFile(Path mappingFile) throws IOException {
        Yaml yaml = new Yaml();
        try (var input = Files.newInputStream(mappingFile)) {
            Object loaded = yaml.load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                return null;
            }
            Map<String, String> headerMapping = DslMaps.stringMap(root.get("headerMapping"));
            Map<String, String> eventTypeMapping = DslMaps.stringMap(root.get("eventTypeMapping"));
            Map<String, String> bodyFieldMapping = DslMaps.stringMap(root.get("bodyFieldMapping"));
            Map<String, EventMappingModels.TransformRule> transforms = parseTransforms(root.get("transforms"));
            EventMappingModels.LegacyDetection legacyDetection = parseLegacyDetection(root.get("legacyDetection"));

            return new ParsedEventMapping(
                    DslMaps.stringValue(root.get("sourceSystem")),
                    DslMaps.stringValue(root.get("description")),
                    headerMapping,
                    eventTypeMapping,
                    bodyFieldMapping,
                    transforms,
                    legacyDetection,
                    buildMappingViews(bodyFieldMapping, transforms)
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, EventMappingModels.TransformRule> parseTransforms(Object transformsObj) {
        Map<String, EventMappingModels.TransformRule> result = new LinkedHashMap<>();
        if (!(transformsObj instanceof Map<?, ?> transforms)) {
            return result;
        }
        transforms.forEach((targetField, value) -> {
            if (value instanceof Map<?, ?> ruleMap) {
                result.put(String.valueOf(targetField), new EventMappingModels.TransformRule(
                        DslMaps.stringValue(ruleMap.get("rule")),
                        DslMaps.stringValue(ruleMap.get("from")),
                        DslMaps.stringValue(ruleMap.get("description")),
                        ruleMap.get("scale") != null ? Integer.parseInt(ruleMap.get("scale").toString()) : null
                ));
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private EventMappingModels.LegacyDetection parseLegacyDetection(Object legacyObj) {
        if (!(legacyObj instanceof Map<?, ?> legacyMap)) {
            return null;
        }
        return new EventMappingModels.LegacyDetection(
                DslMaps.stringList(legacyMap.get("headersPresent")),
                DslMaps.stringList(legacyMap.get("headersAbsent"))
        );
    }

    private List<EventMappingModels.FieldMappingView> buildMappingViews(
            Map<String, String> bodyFieldMapping,
            Map<String, EventMappingModels.TransformRule> transforms) {
        List<EventMappingModels.FieldMappingView> views = new ArrayList<>();
        for (Map.Entry<String, String> entry : bodyFieldMapping.entrySet()) {
            EventMappingModels.TransformRule transform = transforms.get(entry.getValue());
            String transformLabel = transform != null ? transform.description() : null;
            if (transformLabel == null && transform != null && transform.rule() != null) {
                transformLabel = transform.rule();
            }
            views.add(new EventMappingModels.FieldMappingView(
                    entry.getKey(),
                    entry.getValue(),
                    transformLabel
            ));
        }
        return views;
    }

    private String mappingId(String configFile) {
        return configFile.replace("-event-mapping.yaml", "");
    }

    public record ParsedEventMapping(
            String sourceSystem,
            String description,
            Map<String, String> headerMapping,
            Map<String, String> eventTypeMapping,
            Map<String, String> bodyFieldMapping,
            Map<String, EventMappingModels.TransformRule> transforms,
            EventMappingModels.LegacyDetection legacyDetection,
            List<EventMappingModels.FieldMappingView> bodyMappings
    ) {
        EventMappingModels.MappingConfigView toConfigView() {
            return new EventMappingModels.MappingConfigView(
                    sourceSystem,
                    description,
                    headerMapping,
                    eventTypeMapping,
                    bodyFieldMapping,
                    bodyMappings
            );
        }
    }

    public record RegisteredEventMappingView(
            String mappingId,
            String configFile,
            String sourceSystem,
            String description,
            String bindingId,
            String topicAlias,
            String consumerGroup,
            Map<String, String> headerMapping,
            Map<String, String> eventTypeMapping,
            List<EventMappingModels.FieldMappingView> bodyMappings
    ) {
    }
}
