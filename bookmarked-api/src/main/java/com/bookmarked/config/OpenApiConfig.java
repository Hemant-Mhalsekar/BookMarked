package com.bookmarked.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui with a "Authorize" button for the Bearer token. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookmarkedOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BookMarked API")
                        .version("0.1.0")
                        .description("Online bookstore for paperback, hardcover and e-books."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
