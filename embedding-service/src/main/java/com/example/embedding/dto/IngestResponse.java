package com.example.embedding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Réponse à une ingestion de document")
public record IngestResponse(

        @Schema(description = "Identifiant UUID attribué au document dans le vector store")
        String id,

        @Schema(description = "Message de confirmation")
        String message
) {}
