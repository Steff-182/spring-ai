package com.example.springai.model;

public record DocumentStatsResult(
        String fundCode,
        String typologyCode,
        long totalDocuments,
        int averagePages,
        String details) {
}