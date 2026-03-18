package com.example.embedding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Requête d'ingestion d'un document dans le vector store")
public record IngestRequest(

        @Schema(description = "Contenu textuel à vectoriser et stocker", requiredMode = Schema.RequiredMode.REQUIRED)
        String content,

        @Schema(description = "Métadonnées associées au document (kind, typologyCode, fundCode, …)")
        Map<String, Object> metadata
) {}
