package com.example.springai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.example.springai.model.DocumentStatsResult;
import com.example.springai.model.TypologyCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DocumentKnowledgeBaseService {

    private static final String CATALOG_PATH = "classpath:data/document-routing-catalog.json";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private RoutingCatalog catalog;
    private Map<String, FundEntry> fundsByCode;
    private Map<String, TypologyEntry> typologiesByCode;

    public DocumentKnowledgeBaseService(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void loadCatalog() {
        Resource resource = resourceLoader.getResource(CATALOG_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            this.catalog = objectMapper.readValue(inputStream, RoutingCatalog.class);
            this.fundsByCode = catalog.funds().stream()
                    .collect(Collectors.toMap(fund -> normalize(fund.code()), fund -> fund));
            this.typologiesByCode = catalog.typologies().stream()
                    .collect(Collectors.toMap(typology -> normalize(typology.code()), typology -> typology));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to load document routing catalog", exception);
        }
    }

    public String buildCatalogExcerpt(int maxTypologies) {
        return catalog.typologies().stream()
                .limit(maxTypologies)
                .map(typology -> "- " + typology.code() + " | " + typology.label() + " | fonds="
                        + typology.fundCode() + " | type=" + typology.documentType() + " | indices="
                        + String.join(", ", typology.keywords()))
                .collect(Collectors.joining("\n"));
    }

    public List<TypologyCandidate> findCandidateTypologies(String text, int limit) {
        String normalizedText = normalize(text);
        return catalog.typologies().stream()
                .map(typology -> Map.entry(score(typology, normalizedText), toCandidate(typology,
                        scoreReason(typology, normalizedText))))
                .sorted(Map.Entry.<Integer, TypologyCandidate>comparingByKey(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getValue().typologyCode()))
                .limit(limit)
                .map(Map.Entry::getValue)
                .toList();
    }

    public TypologyCandidate getTypologyDetails(String typologyCode) {
        TypologyEntry typology = typologiesByCode.get(normalize(typologyCode));
        if (typology == null) {
            return new TypologyCandidate("INCONNU", "Typologie introuvable", "INCONNU", "AUTRE",
                    "Aucune typologie ne correspond a ce code dans le jeu de donnees factice.",
                    "Verifier le code puis relancer une recherche outillee ou RAG.");
        }
        return toCandidate(typology, "Typologie issue du referentiel documentaire factice.");
    }

    public DocumentStatsResult getStatistics(String fundCode, String typologyCode) {
        FundEntry fund = fundsByCode.get(normalize(fundCode));
        if (fund == null) {
            return new DocumentStatsResult("INCONNU", typologyCode, 0, 0,
                    "Fonds introuvable dans le referentiel factice.");
        }

        if (typologyCode == null || typologyCode.isBlank()) {
            return new DocumentStatsResult(fund.code(), null, fund.documentCount(), fund.averagePages(),
                    "Volume total du fonds " + fund.code() + " (jeu de donnees factice). Typologies principales: "
                            + String.join(", ", fund.typologies()));
        }

        TypologyEntry typology = typologiesByCode.get(normalize(typologyCode));
        if (typology == null || !normalize(fund.code()).equals(normalize(typology.fundCode()))) {
            return new DocumentStatsResult(fund.code(), typologyCode, 0, 0,
                    "La typologie demandee n'est pas rattachee a ce fonds dans le referentiel factice.");
        }

        return new DocumentStatsResult(fund.code(), typology.code(), typology.documentCount(), typology.averagePages(),
                "Statistiques factices pour " + typology.code() + " - " + typology.label()
                        + ". Strategie d'extraction recommandee: " + typology.tokenStrategy());
    }

    public List<Document> buildKnowledgeDocuments() {
        List<Document> documents = new ArrayList<>();

        for (FundEntry fund : catalog.funds()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("kind", "fund");
            metadata.put("fundCode", fund.code());
            metadata.put("documentType", "DOMAIN");
            documents.add(new Document(
                    "Fonds " + fund.code() + " - " + fund.label() + ". Description: " + fund.description()
                            + ". Typologies principales: " + String.join(", ", fund.typologies())
                            + ". Politique de tri: " + fund.routingPolicy(),
                    metadata));
        }

        for (TypologyEntry typology : catalog.typologies()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("kind", "typology");
            metadata.put("fundCode", typology.fundCode());
            metadata.put("typologyCode", typology.code());
            metadata.put("documentType", typology.documentType());
            documents.add(new Document(
                    typology.code() + " - " + typology.label() + ". Fonds cible: " + typology.fundCode()
                            + ". Type de document: " + typology.documentType() + ". Description: "
                            + typology.description() + ". Indices OCR utiles: " + String.join(", ", typology.keywords())
                            + ". Strategie de tokens: " + typology.tokenStrategy(),
                    metadata));
        }

        documents.add(new Document(
                "Guide de performance pour la classification documentaire: ne pas envoyer systematiquement l'OCR integral d'un compromis de vente de 25 pages. Privilegier d'abord la premiere page, les pages de signatures, les pages contenant les parties, les references dossier, les montants et les clauses clefs. Utiliser le RAG ou les tools pour injecter le referentiel fonds/typologies au lieu de le recopier dans chaque prompt.",
                Map.of("kind", "playbook", "fundCode", "GLOBAL", "documentType", "GUIDE")));

        return documents;
    }

    private int score(TypologyEntry typology, String normalizedText) {
        int score = 0;
        for (String keyword : typology.keywords()) {
            if (normalizedText.contains(normalize(keyword))) {
                score += 3;
            }
        }
        if (normalizedText.contains(normalize(typology.label()))) {
            score += 5;
        }
        if (normalizedText.contains(normalize(typology.documentType()))) {
            score += 2;
        }
        return score;
    }

    private String scoreReason(TypologyEntry typology, String normalizedText) {
        List<String> matchedKeywords = typology.keywords().stream()
                .filter(keyword -> normalizedText.contains(normalize(keyword)))
                .toList();

        if (!matchedKeywords.isEmpty()) {
            return "Correspondance sur les indices OCR: " + String.join(", ", matchedKeywords);
        }

        return "Correspondance faible ; utiliser un tool ou le RAG pour confirmer le classement.";
    }

    private TypologyCandidate toCandidate(TypologyEntry typology, String reason) {
        return new TypologyCandidate(
                typology.code(),
                typology.label(),
                typology.fundCode(),
                typology.documentType(),
                reason,
                typology.tokenStrategy());
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("é", "e")
                .replace("è", "e")
                .replace("ê", "e")
                .replace("à", "a")
                .replace("ù", "u")
                .replace("ç", "c");
    }

    public record RoutingCatalog(List<FundEntry> funds, List<TypologyEntry> typologies) {
    }

    public record FundEntry(
            String code,
            String label,
            String description,
            String routingPolicy,
            long documentCount,
            int averagePages,
            List<String> typologies) {
    }

    public record TypologyEntry(
            String code,
            String label,
            String fundCode,
            String documentType,
            String description,
            List<String> keywords,
            String tokenStrategy,
            long documentCount,
            int averagePages) {
    }
}