package bank.internalgateway.gateway.dsl;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import bank.internalgateway.gateway.config.GatewayProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class DslLoader {

    private static final Logger log = LoggerFactory.getLogger(DslLoader.class);

    private final GatewayProperties properties;
    private Map<String, Object> openingModule;
    private Map<String, Object> messagingModule;

    public DslLoader(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void load() throws IOException {
        Path dslDir = Path.of(properties.dslPath());
        openingModule = loadYaml(dslDir.resolve("deposit-opening-gateway.dsl.yaml"));
        messagingModule = loadYaml(dslDir.resolve("deposit-messaging-gateway.dsl.yaml"));
        log.info("Loaded DSL modules from {}", dslDir);
    }

    public Map<String, Object> openingModule() {
        return openingModule;
    }

    public Map<String, Object> messagingModule() {
        return messagingModule;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (var input = Files.newInputStream(path)) {
            return yaml.load(input);
        }
    }
}
