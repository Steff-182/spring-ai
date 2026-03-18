package com.example.springai.client.dto;

/**
 * DTO miroir de QueryRequest dans l'embedding-service.
 */
public record EmbeddingQueryRequest(
        String query,
        int topK,
        String filterExpression
) {}
