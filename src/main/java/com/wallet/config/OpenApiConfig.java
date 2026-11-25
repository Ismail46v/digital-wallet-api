package com.wallet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI: {@code /swagger-ui.html}, OpenAPI JSON: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI walletOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Digital Wallet API")
                .version("v1")
                .description("Wallets, atomic transfers with idempotency, tags and transaction history."));
    }
}
