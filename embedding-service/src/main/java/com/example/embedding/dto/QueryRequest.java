package com.example.embedding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Requête de recherche sémantique")
public record QueryRequest(

        @Schema(description = "Texte de la requête à vectoriser", requiredMode = Schema.RequiredMode.REQUIRED)
        String query,

        @Schema(description = "Nombre maximum de résultats à retourner (défaut : 3)", defaultValue = "3")
        int topK,

        @Schema(description = "Expression de filtre sur les métadonnées, ex: kind == 'typology'")
        String filterExpression
) {
    public QueryRequest {
        if (topK <= 0) topK = 3;
    }
}
