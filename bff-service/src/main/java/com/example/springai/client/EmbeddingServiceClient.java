package com.example.springai.client;

import com.example.springai.client.dto.EmbeddingQueryRequest;
import com.example.springai.client.dto.EmbeddingSimilarDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Client REST vers l'embedding-service (Ollama + PgVector).
 * Utilisé par la route "hybrid" : embedding local → similarity search → top-k envoyés à OpenAI.
 */
@Component
public class EmbeddingServiceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingServiceClient.class);
    private final RestClient restClient;

    public EmbeddingServiceClient(
            @Value("${app.embedding-service.base-url:http://localhost:8090/api}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Envoie une requête de recherche sémantique vers l'embedding-service.
     *
     * @param query            Le texte OCR à comparer au corpus
     * @param topK             Nombre de résultats souhaités
     * @param filterExpression Filtre sur métadonnées, ex: "kind == 'typology'"
     * @return Liste des documents les plus similaires avec leur score
     */
    public List<EmbeddingSimilarDocument> search(String query, int topK, String filterExpression) {
        EmbeddingQueryRequest request = new EmbeddingQueryRequest(query, topK, filterExpression);
        LOGGER.info("[BFF][EMBEDDING_ROUTE] calling embedding-service /embed/search topK={} filter={} queryChars={}",
                topK,
                filterExpression,
            query == null ? 0 : query.length());

        List<EmbeddingSimilarDocument> results = restClient.post()
                .uri("/embed/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (results == null || results.isEmpty()) {
            LOGGER.info("[BFF][EMBEDDING_ROUTE] embedding-service returned 0 candidates");
            return List.of();
        }

        for (int i = 0; i < results.size(); i++) {
            EmbeddingSimilarDocument result = results.get(i);
            LOGGER.info("[BFF][EMBEDDING_ROUTE] rank={} typology={} cosineDistance={} metadata={}",
                    i + 1,
                    result.typologyCode(),
                    result.score(),
                    result.metadata());
        }
        return results;
    }
}
