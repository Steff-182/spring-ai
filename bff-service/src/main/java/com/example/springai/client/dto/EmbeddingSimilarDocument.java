package com.example.springai.client.dto;

import java.util.Map;

/**
 * Représentation d'un document retourné par l'embedding-service.
 */
public record EmbeddingSimilarDocument(
        String id,
        String content,
        Map<String, Object> metadata,
        double score
) {
    /** Raccourci : code de la typologie depuis les métadonnées. */
    public String typologyCode() {
        return metadata != null ? (String) metadata.get("typologyCode") : null;
    }

    /** Raccourci : libellé de la typologie depuis les métadonnées. */
    public String label() {
        return metadata != null ? (String) metadata.get("label") : null;
    }

    /** Raccourci : code du fonds depuis les métadonnées. */
    public String fundCode() {
        return metadata != null ? (String) metadata.get("fundCode") : null;
    }
}
