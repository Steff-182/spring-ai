package com.example.embedding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Document similaire retourné par la recherche sémantique")
public record SimilarDocumentResponse(

        @Schema(description = "Identifiant du document dans le vector store")
        String id,

        @Schema(description = "Contenu textuel du document")
        String content,

        @Schema(description = "Métadonnées associées (kind, typologyCode, fundCode, documentType, …)")
        Map<String, Object> metadata,

        @Schema(description = "Score de similarité cosinus [0..1], plus proche de 1 = plus similaire")
        double score
) {}
