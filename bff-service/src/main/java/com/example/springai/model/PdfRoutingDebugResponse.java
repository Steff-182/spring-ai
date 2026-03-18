package com.example.springai.model;

import java.util.List;

public record PdfRoutingDebugResponse(
        String strategy,
        String fileName,
        int pageCount,
        int extractedTextLength,
        String extractedTextPreview,
        String extractionMode,
        boolean ocrAttempted,
        boolean ocrSucceeded,
        String extractionDiagnostic,
        String candidateTypologyHints,
        int retrievalConfidenceScore,
        String retrievalConfidenceLevel,
        String retrievalConfidenceExplanation,
        String hybridDecisionMode,
        double retrievalGap12,
        double retrievalSpread,
        double retrievalMajorityRatio,
        String excerptSentToModel,
        String finalPromptPreview,
        boolean advisorTruncated,
        int originalPromptLength,
        int excerptPromptLength,
        List<PdfPagePreview> pagePreviews,
        List<String> retrievedContextSnippets,
        String readerExplanation,
        String llmRawResponse,
        DocumentRoutingResponse routing) {
}