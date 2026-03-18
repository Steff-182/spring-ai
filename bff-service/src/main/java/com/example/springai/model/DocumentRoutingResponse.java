package com.example.springai.model;

public record DocumentRoutingResponse(
        String strategy,
        String documentType,
        String fundCode,
        String typologyCode,
        String rationale,
        String tokenAdvice) {
}