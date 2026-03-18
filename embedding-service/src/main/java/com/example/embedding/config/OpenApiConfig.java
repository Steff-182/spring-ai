package com.example.embedding.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI embeddingServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Embedding Service API")
                        .version("1.0")
                        .description("""
                                Service d'embedding sémantique local via Ollama (nomic-embed-text).
                                
                                - **/ingest** : vectorise et stocke un document dans PgVector
                                - **/search** : renvoie les top-k documents les plus proches d'une requête
                                - **/typologies** : gestion du chargement des prototypes de typologies documentaires
                                """));
    }
}
