package com.example.embedding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Résultat du (re)chargement des typologies")
public record TypologyReloadResponse(

        @Schema(description = "Nombre de typologies chargées")
        int loaded,

        @Schema(description = "Message informatif")
        String message
) {}
