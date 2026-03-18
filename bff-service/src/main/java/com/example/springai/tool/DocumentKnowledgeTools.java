package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.springai.model.DocumentStatsResult;
import com.example.springai.model.TypologySearchResult;
import com.example.springai.service.DocumentKnowledgeBaseService;

@Component
public class DocumentKnowledgeTools {

    private final DocumentKnowledgeBaseService documentKnowledgeBaseService;

    public DocumentKnowledgeTools(DocumentKnowledgeBaseService documentKnowledgeBaseService) {
        this.documentKnowledgeBaseService = documentKnowledgeBaseService;
    }

    @Tool(description = "Recherche jusqu'a 5 typologies candidates a partir d'un extrait OCR de document bancaire. A utiliser pour proposer un fonds documentaire et une typologie T suivie de 6 chiffres.")
    public TypologySearchResult findCandidateTypologies(
            @ToolParam(description = "Extrait OCR nettoye ou resume du document a classer") String documentExcerpt) {
        return new TypologySearchResult(documentKnowledgeBaseService.findCandidateTypologies(documentExcerpt, 5));
    }

    @Tool(description = "Retourne des statistiques documentaires factices sur un fonds, et optionnellement sur une typologie donnee dans ce fonds.")
    public DocumentStatsResult getDocumentStatistics(
            @ToolParam(description = "Code du fonds documentaire, par exemple ACC, CREDIMMO ou SUCC") String fundCode,
            @ToolParam(description = "Code de typologie au format T123456", required = false) String typologyCode) {
        return documentKnowledgeBaseService.getStatistics(fundCode, typologyCode);
    }
}