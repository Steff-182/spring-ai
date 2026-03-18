package com.example.springai.model;

public record TypologyCandidate(
        String typologyCode,
        String label,
        String fundCode,
        String documentType,
        String reason,
        String tokenStrategy) {
}