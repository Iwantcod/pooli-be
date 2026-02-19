package com.pooli.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pooliOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pooli API")
                        .description("Pooli backend API docs")
                        .version("v1")
                        .contact(new Contact().name("Pooli Team")));
    }
}
