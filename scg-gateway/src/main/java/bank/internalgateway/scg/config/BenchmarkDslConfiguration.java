package bank.internalgateway.scg.config;

import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class BenchmarkDslConfiguration {

    @Bean
    BenchmarkRouteRegistry benchmarkRouteRegistry(
            ScgGatewayProperties properties,
            ApplicationEventPublisher eventPublisher) throws IOException {
        BenchmarkRouteRegistry registry = new BenchmarkRouteRegistry(Path.of(properties.dslPath()));
        registry.loadInitial();
        registry.setReloadListener(module -> eventPublisher.publishEvent(new RefreshRoutesEvent(registry)));
        return registry;
    }
}
