package bank.internalgateway.gateway.dsl;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class BenchmarkDslConfiguration {

    @Bean
    BenchmarkRouteRegistry benchmarkRouteRegistry(GatewayProperties properties) throws IOException {
        BenchmarkRouteRegistry registry = new BenchmarkRouteRegistry(Path.of(properties.dslPath()));
        registry.loadInitial();
        return registry;
    }
}
