package com.example.springai.config;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.springai.advisor.DocumentExcerptAdvisor;
import com.example.springai.service.DocumentKnowledgeBaseService;

@Configuration
public class DocumentAiDemoConfig {

    @Bean
    DocumentExcerptAdvisor documentExcerptAdvisor() {
        return new DocumentExcerptAdvisor(2400, 1600, 600, -100);
    }

    @Bean("documentKnowledgeVectorStore")
    @Profile("!local")
    VectorStore documentKnowledgeVectorStoreOpenAi(
            JdbcTemplate jdbcTemplate,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }

    @Bean("documentKnowledgeVectorStore")
    @Profile("local")
    VectorStore documentKnowledgeVectorStoreOllama(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(768)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }

    /**
     * Charge les prototypes de typologies dans PgVector au démarrage,
     * uniquement si la table est vide (idempotent).
     */
    @Bean
    CommandLineRunner loadTypologyPrototypes(
            @Qualifier("documentKnowledgeVectorStore") VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            DocumentKnowledgeBaseService documentKnowledgeBaseService) {
        return args -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vector_store WHERE metadata->>'kind' = 'typology'",
                    Integer.class);
            if (count == null || count == 0) {
                vectorStore.add(List.copyOf(documentKnowledgeBaseService.buildKnowledgeDocuments()));
            }
        };
    }
}