package bank.internalgateway.scg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScgGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScgGatewayApplication.class, args);
    }
}
