package com.example.springai.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.example.springai.client.EmbeddingServiceClient;
import com.example.springai.client.dto.EmbeddingSimilarDocument;
import com.example.springai.model.DocumentClassificationResponse;
import com.example.springai.model.DocumentRoutingResponse;
import com.example.springai.model.PdfRoutingDebugResponse;
import com.example.springai.model.TypologyCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.springai.advisor.DocumentExcerptAdvisor;
import com.example.springai.tool.DocumentKnowledgeTools;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DocumentClassificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentClassificationService.class);
    private static final int MAX_LOG_INPUT_LENGTH = 200;
    private static final TokenCountEstimator TOKEN_COUNT_ESTIMATOR = new JTokkitTokenCountEstimator();
    private static final int HYBRID_RETRIEVAL_TOP_N = 10;
    private static final int HYBRID_LLM_TOP_K = 3;
    private static final String CONTEXT_KEY_FINAL_SYSTEM_PROMPT = "FINAL_SYSTEM_PROMPT";
    private static final String CONTEXT_KEY_FINAL_USER_PROMPT = "FINAL_USER_PROMPT";
    private static final String CONTEXT_KEY_HYBRID_DECISION_MODE = "HYBRID_DECISION_MODE";
    private static final String CONTEXT_KEY_RETRIEVAL_CONFIDENCE_SCORE = "RETRIEVAL_CONFIDENCE_SCORE";
    private static final String CONTEXT_KEY_RETRIEVAL_CONFIDENCE_LEVEL = "RETRIEVAL_CONFIDENCE_LEVEL";
    private static final String CONTEXT_KEY_RETRIEVAL_CONFIDENCE_EXPLANATION = "RETRIEVAL_CONFIDENCE_EXPLANATION";
    private static final String CONTEXT_KEY_RETRIEVAL_GAP12 = "RETRIEVAL_GAP12";
    private static final String CONTEXT_KEY_RETRIEVAL_SPREAD = "RETRIEVAL_SPREAD";
    private static final String CONTEXT_KEY_RETRIEVAL_MAJORITY_RATIO = "RETRIEVAL_MAJORITY_RATIO";

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final PromptTemplateService promptTemplateService;
    private final DocumentKnowledgeBaseService documentKnowledgeBaseService;
    private final DocumentKnowledgeTools documentKnowledgeTools;
    private final DocumentExcerptAdvisor documentExcerptAdvisor;
    private final VectorStore documentKnowledgeVectorStore;
    private final ObjectMapper objectMapper;
    private final EmbeddingServiceClient embeddingServiceClient;

    public DocumentClassificationService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            PromptTemplateService promptTemplateService,
            DocumentKnowledgeBaseService documentKnowledgeBaseService,
            DocumentKnowledgeTools documentKnowledgeTools,
            DocumentExcerptAdvisor documentExcerptAdvisor,
            @Qualifier("documentKnowledgeVectorStore") VectorStore documentKnowledgeVectorStore,
            ObjectMapper objectMapper,
            EmbeddingServiceClient embeddingServiceClient) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatMemory = chatMemory;
        this.promptTemplateService = promptTemplateService;
        this.documentKnowledgeBaseService = documentKnowledgeBaseService;
        this.documentKnowledgeTools = documentKnowledgeTools;
        this.documentExcerptAdvisor = documentExcerptAdvisor;
        this.documentKnowledgeVectorStore = documentKnowledgeVectorStore;
        this.objectMapper = objectMapper;
        this.embeddingServiceClient = embeddingServiceClient;
    }

    public DocumentClassificationResponse classify(String text) {
        LOGGER.info("DocumentClassificationService.classify input='{}'", truncateForLog(text));

        DocumentRoutingResponse response = routeWithBasicPrompt(text);

        return new DocumentClassificationResponse(response.documentType() == null ? "AUTRE" : response.documentType().trim());
    }

    public DocumentRoutingResponse routeWithBasicPrompt(String text) {
        return executeRoutingScenarioDetailed(
                "prompt-only",
                text,
                ChatMemory.DEFAULT_CONVERSATION_ID,
                null,
                chatClientBuilder.clone()
                        .defaultAdvisors(documentExcerptAdvisor)
                        .build()
                        .prompt()
                        .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                                "strategy_name", "prompt-only",
                                "knowledge_source", "un extrait compact du referentiel documentaire embarque dans le prompt systeme",
                                "extra_instructions", "N'invente pas une typologie hors format T + 6 chiffres. Si le document reste ambigu, renvoie INCONNU.",
                                "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 8))))
                            .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))))
                        .routing();
    }

    public DocumentRoutingResponse routeWithMemory(String conversationId, String text) {
                    return executeRoutingScenarioDetailed(
                "memory",
                text,
                conversationId,
                null,
                chatClientBuilder.clone()
                        .defaultAdvisors(documentExcerptAdvisor, MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .build()
                        .prompt()
                        .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)))
                        .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                                "strategy_name", "memory",
                                "knowledge_source", "la memoire conversationnelle precedente et un mini-catalogue embarque",
                                        "extra_instructions", "Utilise les informations memorisees pour conserver une typologie coherente quand le contexte utilisateur le justifie. Si aucune memoire utile n'est disponible, reste prudent.",
                                "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 6))))
                            .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))))
                        .routing();
    }

    public DocumentRoutingResponse routeWithTools(String text) {
                    return executeRoutingScenarioDetailed(
                "tools",
                text,
                ChatMemory.DEFAULT_CONVERSATION_ID,
                null,
                chatClientBuilder.clone()
                        .defaultAdvisors(documentExcerptAdvisor)
                        .defaultTools(documentKnowledgeTools)
                        .build()
                        .prompt()
                        .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                                "strategy_name", "tools",
                                "knowledge_source", "les tools du referentiel documentaire et des statistiques factices",
                                "extra_instructions", "Avant de conclure, appelle le tool de recherche de typologies si l'extrait OCR contient plusieurs interpretations possibles.",
                                "routing_catalog_excerpt", "Le catalogue complet n'est pas dans le prompt ; va le chercher via les tools.")))
                            .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))))
                        .routing();
    }

    public DocumentRoutingResponse routeWithRag(String text, String fundCode) {
        return executeRagRoute(text, fundCode).routing();
    }

    public DocumentRoutingResponse routeWithHybrid(String text) {
        return executeHybridRoute(text).routing();
    }

    public PdfRoutingDebugResponse routePdf(
            String strategy,
            String conversationId,
            String fundCode,
            PdfDocumentReaderService.PdfExtractionResult extractionResult) {
            LOGGER.info("[BFF][ROUTE_PDF] strategy={} extractionMode={} extractedChars={} file={} pages={}",
                    normalizeStrategy(strategy),
                    extractionResult.extractionMode(),
                    extractionResult.combinedText() == null ? 0 : extractionResult.combinedText().length(),
                    extractionResult.fileName(),
                    extractionResult.pageCount());
            if (!hasMeaningfulExtractedText(extractionResult.combinedText())) {
                return buildNoTextExtractedResponse(strategy, extractionResult);
            }

        RouteExecution execution = switch (normalizeStrategy(strategy)) {
            case "memory" -> executeMemoryRoute(extractionResult.combinedText(), conversationId);
            case "tools" -> executeToolsRoute(extractionResult.combinedText());
            case "rag" -> executeRagRoute(extractionResult.combinedText(), fundCode);
            case "hybrid" -> executeHybridRoute(extractionResult.combinedText());
            default -> executeBasicRoute(extractionResult.combinedText());
        };

        boolean usesFullText = "rag".equals(execution.strategy()) || "hybrid".equals(execution.strategy());
        String defaultExcerpt = usesFullText
            ? extractionResult.combinedText()
            : documentExcerptAdvisor.createExcerpt(extractionResult.combinedText());
        String candidateTypologyHints = usesFullText
            ? contextString(execution.context(), "RAG_SEMANTIC_CANDIDATES", "")
            : buildRoutingCandidateExcerpt(extractionResult.combinedText(), 6);
        int retrievalConfidenceScore = contextInt(execution.context(), CONTEXT_KEY_RETRIEVAL_CONFIDENCE_SCORE, 0);
        String retrievalConfidenceLevel = contextString(execution.context(), CONTEXT_KEY_RETRIEVAL_CONFIDENCE_LEVEL, "N/A");
        String retrievalConfidenceExplanation = contextString(execution.context(), CONTEXT_KEY_RETRIEVAL_CONFIDENCE_EXPLANATION, "");
        String hybridDecisionMode = contextString(execution.context(), CONTEXT_KEY_HYBRID_DECISION_MODE, "N/A");
        double retrievalGap12 = contextDouble(execution.context(), CONTEXT_KEY_RETRIEVAL_GAP12, 0.0);
        double retrievalSpread = contextDouble(execution.context(), CONTEXT_KEY_RETRIEVAL_SPREAD, 0.0);
        double retrievalMajorityRatio = contextDouble(execution.context(), CONTEXT_KEY_RETRIEVAL_MAJORITY_RATIO, 0.0);
        String excerptSentToModel = usesFullText
            ? extractionResult.combinedText()
            : contextString(execution.context(), DocumentExcerptAdvisor.EXCERPT_TEXT, defaultExcerpt);
        String finalPromptPreview = buildFinalPromptPreview(
            execution.strategy(),
            extractionResult.combinedText(),
            excerptSentToModel,
            execution.context());

        return new PdfRoutingDebugResponse(
            execution.strategy(),
            extractionResult.fileName(),
            extractionResult.pageCount(),
            extractionResult.combinedText().length(),
            preview(extractionResult.combinedText(), 2400),
            extractionResult.extractionMode(),
            extractionResult.ocrAttempted(),
            extractionResult.ocrSucceeded(),
            extractionResult.extractionDiagnostic(),
            candidateTypologyHints,
            retrievalConfidenceScore,
            retrievalConfidenceLevel,
            retrievalConfidenceExplanation,
            hybridDecisionMode,
            retrievalGap12,
            retrievalSpread,
            retrievalMajorityRatio,
            excerptSentToModel,
            finalPromptPreview,
                !usesFullText && contextBoolean(execution.context(), DocumentExcerptAdvisor.WAS_TRUNCATED),
                usesFullText ? extractionResult.combinedText().length() : contextInt(execution.context(), DocumentExcerptAdvisor.ORIGINAL_LENGTH, extractionResult.combinedText().length()),
                usesFullText ? extractionResult.combinedText().length() : contextInt(execution.context(), DocumentExcerptAdvisor.EXCERPT_LENGTH, defaultExcerpt.length()),
            extractionResult.pagePreviews(),
            retrievedContextSnippets(execution.context()),
            extractionResult.readerExplanation(),
            preview(execution.rawResponse(), 2400),
            execution.routing());
            }

        private PdfRoutingDebugResponse buildNoTextExtractedResponse(
            String strategy,
            PdfDocumentReaderService.PdfExtractionResult extractionResult) {
        String normalizedStrategy = normalizeStrategy(strategy);
        String explanation = extractionResult.readerExplanation()
            + " Aucun texte exploitable n'a ete extrait du PDF. Le routage LLM est volontairement interrompu pour eviter une classification fondee uniquement sur le contexte RAG.";

        DocumentRoutingResponse routing = new DocumentRoutingResponse(
            normalizedStrategy,
            "AUTRE",
            "INCONNU",
            "INCONNU",
            "Impossible de classifier ce PDF car aucun texte exploitable n'a ete extrait. Le fichier est probablement scanne sous forme d'image ou ne contient pas de couche texte lisible par PagePdfDocumentReader.",
            "Ajouter une etape OCR avant le routage, puis ne transmettre au LLM que les pages et zones utiles.");

        return new PdfRoutingDebugResponse(
            normalizedStrategy,
            extractionResult.fileName(),
            extractionResult.pageCount(),
            0,
            "",
            extractionResult.extractionMode(),
            extractionResult.ocrAttempted(),
            extractionResult.ocrSucceeded(),
            extractionResult.extractionDiagnostic(),
            "",
            0,
            "N/A",
            "",
            "N/A",
            0.0,
            0.0,
            0.0,
            "",
            "",
            false,
            0,
            0,
            extractionResult.pagePreviews(),
            List.of(),
            explanation,
            "LLM skipped: no extracted text available.",
            routing);
        }

        private RouteExecution executeBasicRoute(String text) {
        return executeRoutingScenarioDetailed(
            "prompt-only",
            text,
            ChatMemory.DEFAULT_CONVERSATION_ID,
            null,
            chatClientBuilder.clone()
                .defaultAdvisors(documentExcerptAdvisor)
                .build()
                .prompt()
                .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "prompt-only",
                    "knowledge_source", "un extrait compact du referentiel documentaire embarque dans le prompt systeme",
                    "extra_instructions", "N'invente pas une typologie hors format T + 6 chiffres. Si le document reste ambigu, renvoie INCONNU.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 8))))
                .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))));
        }

        private RouteExecution executeMemoryRoute(String text, String conversationId) {
        return executeRoutingScenarioDetailed(
            "memory",
            text,
            conversationId,
            null,
            chatClientBuilder.clone()
                .defaultAdvisors(documentExcerptAdvisor, MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build()
                .prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)))
                .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "memory",
                    "knowledge_source", "la memoire conversationnelle precedente et un mini-catalogue embarque",
                    "extra_instructions", "Utilise les informations memorisees pour conserver une typologie coherente quand le contexte utilisateur le justifie. Si aucune memoire utile n'est disponible, reste prudent.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 6))))
                .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))));
        }

        private RouteExecution executeToolsRoute(String text) {
        return executeRoutingScenarioDetailed(
            "tools",
            text,
            ChatMemory.DEFAULT_CONVERSATION_ID,
            null,
            chatClientBuilder.clone()
                .defaultAdvisors(documentExcerptAdvisor)
                .defaultTools(documentKnowledgeTools)
                .build()
                .prompt()
                .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "tools",
                    "knowledge_source", "les tools du referentiel documentaire et des statistiques factices",
                    "extra_instructions", "Avant de conclure, appelle le tool de recherche de typologies si l'extrait OCR contient plusieurs interpretations possibles.",
                    "routing_catalog_excerpt", "Le catalogue complet n'est pas dans le prompt ; va le chercher via les tools.")))
                .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text))));
        }

    private RouteExecution executeRagRoute(String text, String fundCode) {
        // Top-3 typologies by semantic similarity â€” no fund filter, no truncation advisor
        List<Document> topTypologies = documentKnowledgeVectorStore.similaritySearch(
            SearchRequest.builder().query(text).topK(3).filterExpression("kind == 'typology'").build());

        String semanticCandidates = topTypologies.isEmpty()
            ? "Aucune typologie similaire trouvee dans le vector store."
            : topTypologies.stream()
                .map(doc -> "- " + doc.getMetadata().getOrDefault("typologyCode", "N/A")
                    + " | type=" + doc.getMetadata().getOrDefault("documentType", "N/A")
                    + " | " + preview(doc.getText(), 200))
                .collect(Collectors.joining("\n"));

        ChatClient.ChatClientRequestSpec requestSpec = chatClientBuilder.clone()
            .build()
            .prompt()
            .system(promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                "strategy_name", "rag",
                "knowledge_source", "les 3 typologies les plus proches par similarite semantique (vector store)",
                "extra_instructions", "Choisis UNIQUEMENT parmi les 3 typologies candidates ci-dessous. Si aucune ne correspond parfaitement, renvoie typologyCode=INCONNU.",
                "routing_catalog_excerpt", semanticCandidates)))
            .user(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text)));

        RouteExecution base = executeRoutingScenarioDetailed("rag", text, ChatMemory.DEFAULT_CONVERSATION_ID, null, requestSpec);

        // Enrich context so the debug view can display retrieved typologies (step 3) and candidates (step 4)
        Map<String, Object> enrichedContext = new HashMap<>(base.context() != null ? base.context() : Map.of());
        enrichedContext.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, topTypologies);
        enrichedContext.put("RAG_SEMANTIC_CANDIDATES", semanticCandidates);
        return new RouteExecution(base.strategy(), base.routing(), base.rawResponse(), enrichedContext);
    }

    /**
     * Route hybride : l'embedding est delegue a l'embedding-service (Ollama local),
     * puis OpenAI arbitre parmi les top-3 typologies retournees.
     */
    private RouteExecution executeHybridRoute(String text) {
        List<EmbeddingSimilarDocument> candidatesTopN = embeddingServiceClient.search(text, HYBRID_RETRIEVAL_TOP_N, "kind == 'typology'");
        List<EmbeddingSimilarDocument> candidatesTop3 = candidatesTopN.stream()
            .limit(HYBRID_LLM_TOP_K)
            .toList();

        RetrievalConfidence confidence = buildRetrievalConfidence(candidatesTopN);
        LOGGER.info("[BFF][RETRIEVAL_CONFIDENCE] topN={} topK={} score={} level={} -> d1={} d2={} gap12={} spread={} majorityType={} majorityRatio={}",
            candidatesTopN.size(),
            candidatesTop3.size(),
            confidence.score(),
            confidence.level(),
            confidence.d1(),
            confidence.d2(),
            confidence.gap12(),
            confidence.spreadTopN(),
            confidence.majorityDocumentType(),
            confidence.majorityRatio());
        LOGGER.info("[BFF][RETRIEVAL_CONFIDENCE][BEGINNER_EXPLANATION] {}",
            confidence.explanation());

        HybridDecisionMode decisionMode = switch (confidence.level()) {
            case "HIGH" -> HybridDecisionMode.HIGH_CONSTRAINED;
            case "MEDIUM" -> HybridDecisionMode.MEDIUM_ADAPTIVE;
            default -> HybridDecisionMode.LOW_SKIP;
        };
        LOGGER.info("[BFF][HYBRID_DECISION] confidenceScore={} confidenceLevel={} decisionMode={}",
                confidence.score(),
                confidence.level(),
                decisionMode.name());

        String semanticCandidates = candidatesTop3.isEmpty()
                ? "Aucune typologie similaire trouvee dans l'embedding-service."
            : candidatesTop3.stream()
                .map(doc -> "- " + doc.typologyCode()
                    + " | type=" + doc.metadata().getOrDefault("documentType", "N/A")
                    + " | label=" + doc.metadata().getOrDefault("label", "N/A")
                    + " | cosineDistance=" + String.format("%.4f", doc.score()))
                        .collect(Collectors.joining("\n"));

        if (decisionMode == HybridDecisionMode.LOW_SKIP) {
            return buildLowConfidenceHybridSkip(text, semanticCandidates, confidence, decisionMode);
        }

        String extraInstructions = decisionMode == HybridDecisionMode.HIGH_CONSTRAINED
                ? "Arbitre uniquement entre les 3 typologies candidates ci-dessous a partir de l'OCR pur. Si le texte reste ambigu, renvoie typologyCode=INCONNU plutot que de forcer un mauvais choix."
                : "Les 3 candidats sont des indices utiles mais pas une contrainte absolue. Si aucun ne colle au texte OCR, tu peux choisir le bon documentType a partir de l'OCR, mais renvoie typologyCode=INCONNU. N'invente jamais un code de typologie absent de la liste.";
        String knowledgeSource = decisionMode == HybridDecisionMode.HIGH_CONSTRAINED
                ? "top-3 embeddings locaux les plus proches, utilises comme cadre strict d'arbitrage OCR"
                : "top-3 embeddings locaux les plus proches, utilises comme indices OCR mais non exhaustifs";

        String systemPrompt = promptTemplateService.render("prompts/document-routing-system.st", Map.of(
            "strategy_name", "hybrid",
            "knowledge_source", knowledgeSource,
            "extra_instructions", extraInstructions,
            "routing_catalog_excerpt", semanticCandidates));
        String userPrompt = promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text));
        int estimatedPromptTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);

        LOGGER.info("[BFF][OPENAI_ARBITRATION] strategy=hybrid decisionMode={} call=starting candidates={} systemChars={} userChars={} estimatedPromptTokens={}",
            decisionMode.name(),
            candidatesTop3.size(),
            systemPrompt.length(),
            userPrompt.length(),
            estimatedPromptTokens);
        LOGGER.info("[BFF][OPENAI_ARBITRATION][SYSTEM]\n{}", systemPrompt);
        LOGGER.info("[BFF][OPENAI_ARBITRATION][USER] chars={} estimatedTokens={}", userPrompt.length(), estimateTokens(userPrompt));

        ChatClient.ChatClientRequestSpec requestSpec = chatClientBuilder.clone()
                .build()
                .prompt()
            .system(systemPrompt)
            .user(userPrompt);

        RouteExecution base = executeRoutingScenarioDetailed("hybrid", text, ChatMemory.DEFAULT_CONVERSATION_ID, null, requestSpec,
            estimatedPromptTokens);

        Map<String, Object> enrichedContext = new HashMap<>(base.context() != null ? base.context() : Map.of());
        enrichedContext.put("RAG_SEMANTIC_CANDIDATES", semanticCandidates);
        enrichedContext.put("HYBRID_RETRIEVAL_CONFIDENCE", confidence.explanation());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_SCORE, confidence.score());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_LEVEL, confidence.level());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_EXPLANATION, confidence.explanation());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_GAP12, confidence.gap12());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_SPREAD, confidence.spreadTopN());
        enrichedContext.put(CONTEXT_KEY_RETRIEVAL_MAJORITY_RATIO, confidence.majorityRatio());
        enrichedContext.put(CONTEXT_KEY_HYBRID_DECISION_MODE, decisionMode.name());
        enrichedContext.put(CONTEXT_KEY_FINAL_SYSTEM_PROMPT, systemPrompt);
        enrichedContext.put(CONTEXT_KEY_FINAL_USER_PROMPT, userPrompt);
        return new RouteExecution(base.strategy(), base.routing(), base.rawResponse(), enrichedContext);
    }

    private RouteExecution buildLowConfidenceHybridSkip(
            String text,
            String semanticCandidates,
            RetrievalConfidence confidence,
            HybridDecisionMode decisionMode) {
        String rationale = "Le retrieval semantique est juge trop ambigu pour justifier un appel OpenAI. Le top-3 actuel n'est pas assez fiable et le referentiel vectoriel doit etre enrichi avant arbitrage distant.";
        String rawResponse = "LLM skipped: hybrid retrieval confidence is LOW, OpenAI call avoided.";
        DocumentRoutingResponse response = new DocumentRoutingResponse(
                "hybrid",
                "AUTRE",
                "INCONNU",
                "INCONNU",
                rationale,
                "Enrichir le referentiel vectoriel avant arbitrage LLM sur ce type de document.");

        Map<String, Object> context = new HashMap<>();
        context.put("RAG_SEMANTIC_CANDIDATES", semanticCandidates);
        context.put("HYBRID_RETRIEVAL_CONFIDENCE", confidence.explanation());
        context.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_SCORE, confidence.score());
        context.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_LEVEL, confidence.level());
        context.put(CONTEXT_KEY_RETRIEVAL_CONFIDENCE_EXPLANATION, confidence.explanation());
        context.put(CONTEXT_KEY_RETRIEVAL_GAP12, confidence.gap12());
        context.put(CONTEXT_KEY_RETRIEVAL_SPREAD, confidence.spreadTopN());
        context.put(CONTEXT_KEY_RETRIEVAL_MAJORITY_RATIO, confidence.majorityRatio());
        context.put(CONTEXT_KEY_HYBRID_DECISION_MODE, decisionMode.name());
        context.put(CONTEXT_KEY_FINAL_SYSTEM_PROMPT, "[LLM SKIPPED] Confidence LOW: aucun prompt envoye a OpenAI pour eviter un cout inutile sur un retrieval juge trop ambigu.");
        context.put(CONTEXT_KEY_FINAL_USER_PROMPT, promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", text)));

        LOGGER.info("[BFF][OPENAI_ARBITRATION] strategy=hybrid decisionMode={} call=skipped reason=LOW_CONFIDENCE", decisionMode.name());
        return new RouteExecution("hybrid", response, rawResponse, context);
    }

    private RetrievalConfidence buildRetrievalConfidence(List<EmbeddingSimilarDocument> candidatesTopN) {
        if (candidatesTopN == null || candidatesTopN.isEmpty()) {
            return new RetrievalConfidence(0, "LOW", 1.0, 1.0, 0.0, 0.0, "N/A", 0.0,
                    "Aucun voisin semantique trouve: score de confiance faible par definition.");
        }

        double d1 = safeDistance(candidatesTopN, 0);
        double d2 = safeDistance(candidatesTopN, 1, d1);
        double dLast = safeDistance(candidatesTopN, candidatesTopN.size() - 1, d2);

        boolean higherIsBetter = d1 >= dLast;
        double gap12 = Math.max(0.0, higherIsBetter ? (d1 - d2) : (d2 - d1));
        double spreadTopN = Math.max(0.0, higherIsBetter ? (d1 - dLast) : (dLast - d1));

        Map<String, Long> byType = candidatesTopN.stream()
                .map(doc -> String.valueOf(doc.metadata().getOrDefault("documentType", "N/A")))
                .collect(Collectors.groupingBy(type -> type, Collectors.counting()));

        Optional<Map.Entry<String, Long>> majority = byType.entrySet().stream()
                .max(Map.Entry.comparingByValue());
        String majorityType = majority.map(Map.Entry::getKey).orElse("N/A");
        double majorityRatio = majority
                .map(entry -> entry.getValue() / (double) candidatesTopN.size())
                .orElse(0.0);

        double gapScore = clamp(gap12 / 0.02);
        double spreadScore = clamp(spreadTopN / 0.06);
        double majorityScore = clamp((majorityRatio - 0.33) / 0.67);

        double weightedGap = gapScore * 0.45;
        double weightedSpread = spreadScore * 0.30;
        double weightedMajority = majorityScore * 0.25;
        int score = (int) Math.round((weightedGap + weightedSpread + weightedMajority) * 100.0);
        String level = score >= 70 ? "HIGH" : (score >= 45 ? "MEDIUM" : "LOW");
        String scoreOrientation = higherIsBetter ? "higher_is_better" : "lower_is_better";
        String explanation = "Le score combine 3 signaux: separation entre le 1er et le 2e voisin (gap12), dispersion du top-N (spread) et coherence de type dans le top-N (majority ratio). "
            + "Orientation detectee: " + scoreOrientation + ". "
                + "Plus le score est eleve, plus le top-3 est fiable pour contraindre le LLM.";

        LOGGER.info("[BFF][RETRIEVAL_CONFIDENCE][COMPONENTS] orientation={} gapRaw={} gapScore={} weightedGap={} spreadRaw={} spreadScore={} weightedSpread={} majorityRaw={} majorityScore={} weightedMajority={}",
            scoreOrientation,
            gap12,
            gapScore,
            weightedGap,
            spreadTopN,
            spreadScore,
            weightedSpread,
            majorityRatio,
            majorityScore,
            weightedMajority);

        return new RetrievalConfidence(score, level, d1, d2, gap12, spreadTopN, majorityType, majorityRatio, explanation);
    }

    private double safeDistance(List<EmbeddingSimilarDocument> values, int index) {
        return safeDistance(values, index, 1.0);
    }

    private double safeDistance(List<EmbeddingSimilarDocument> values, int index, double defaultValue) {
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        int safeIndex = Math.min(Math.max(index, 0), values.size() - 1);
        EmbeddingSimilarDocument value = values.get(safeIndex);
        return value == null ? defaultValue : value.score();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

        private RouteExecution executeRoutingScenarioDetailed(
            String strategy,
            String text,
            String conversationId,
            String fundCode,
            ChatClient.ChatClientRequestSpec requestSpec) {
        return executeRoutingScenarioDetailed(strategy, text, conversationId, fundCode, requestSpec, null);
        }

        private RouteExecution executeRoutingScenarioDetailed(
            String strategy,
            String text,
            String conversationId,
            String fundCode,
            ChatClient.ChatClientRequestSpec requestSpec,
            Integer estimatedPromptTokens) {
        LOGGER.info("DocumentClassificationService.route strategy='{}' inputChars={}", strategy, text == null ? 0 : text.length());

        ChatClientResponse chatClientResponse = requestSpec.call().chatClientResponse();
        logTokenUsage(strategy, chatClientResponse, estimatedPromptTokens);
        String rawResponse = extractContent(chatClientResponse);
        DocumentRoutingResponse response = parseRoutingResponse(strategy, rawResponse, conversationId, fundCode);

        LOGGER.info("DocumentClassificationService.route strategy='{}' output='{}'", strategy, truncateForLog(rawResponse));
        return new RouteExecution(strategy, response, rawResponse, chatClientResponse.context());
        }

        private String extractContent(ChatClientResponse chatClientResponse) {
        if (chatClientResponse == null || chatClientResponse.chatResponse() == null
            || chatClientResponse.chatResponse().getResult() == null
            || chatClientResponse.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        return chatClientResponse.chatResponse().getResult().getOutput().getText();
    }

    private void logTokenUsage(String strategy, ChatClientResponse chatClientResponse, Integer estimatedPromptTokens) {
        if (chatClientResponse == null || chatClientResponse.chatResponse() == null) {
            if (estimatedPromptTokens != null) {
                LOGGER.info("[BFF][LLM_TOKENS] strategy={} estimatedPromptTokens={} promptTokens=unavailable completionTokens=unavailable totalTokens=unavailable",
                        strategy,
                        estimatedPromptTokens);
            }
            return;
        }

        Usage usage = chatClientResponse.chatResponse().getMetadata() == null
                ? null
                : chatClientResponse.chatResponse().getMetadata().getUsage();
        if (usage == null) {
            if (estimatedPromptTokens != null) {
                LOGGER.info("[BFF][LLM_TOKENS] strategy={} estimatedPromptTokens={} promptTokens=unavailable completionTokens=unavailable totalTokens=unavailable",
                        strategy,
                        estimatedPromptTokens);
            }
            return;
        }

        LOGGER.info("[BFF][LLM_TOKENS] strategy={} estimatedPromptTokens={} promptTokens={} completionTokens={} totalTokens={}",
                strategy,
                estimatedPromptTokens,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return TOKEN_COUNT_ESTIMATOR.estimate(text);
    }

    private DocumentRoutingResponse parseRoutingResponse(
            String strategy,
            String rawResponse,
            String conversationId,
            String fundCode) {
        String sanitized = sanitizeJson(rawResponse);

        try {
            DocumentRoutingResponse parsed = objectMapper.readValue(sanitized, DocumentRoutingResponse.class);
            return new DocumentRoutingResponse(
                    defaultIfBlank(parsed.strategy(), strategy),
                    defaultIfBlank(parsed.documentType(), "AUTRE"),
                    defaultIfBlank(parsed.fundCode(), defaultIfBlank(fundCode, "INCONNU")),
                    defaultIfBlank(parsed.typologyCode(), "INCONNU"),
                    defaultIfBlank(parsed.rationale(), "Aucune justification n'a ete retournee."),
                    defaultIfBlank(parsed.tokenAdvice(), defaultTokenAdvice(strategy, conversationId)));
        }
        catch (IOException exception) {
            LOGGER.warn("Unable to parse routing response as JSON for strategy {}", strategy, exception);
            return new DocumentRoutingResponse(
                    strategy,
                    "AUTRE",
                    defaultIfBlank(fundCode, "INCONNU"),
                    "INCONNU",
                    rawResponse == null ? "Reponse vide du modele." : rawResponse.trim(),
                    defaultTokenAdvice(strategy, conversationId));
        }
    }

    private String sanitizeJson(String rawResponse) {
        if (rawResponse == null) {
            return "{}";
        }
        return rawResponse
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private String defaultTokenAdvice(String strategy, String conversationId) {
        return switch (strategy) {
            case "memory" -> "Memoriser seulement les decisions utiles (fonds, typologie candidate, points de doute), pas l'OCR complet. conversationId=" + resolveConversationId(conversationId);
            case "tools" -> "Garder le prompt court et aller chercher le referentiel via tools plutot que d'inliner la taxonomie complete.";
            case "rag" -> "Indexer le referentiel une fois, puis ne recuperer que quelques chunks pertinents par similarite et metadonnees.";
            case "hybrid" -> "Deleguer l'embedding et la similarite au service local, puis demander au LLM distant d'arbitrer uniquement entre les quelques candidats retournes.";
            default -> "Pour un document long, commencer par premiere page, pages de signatures et pages contenant parties, montants et references dossier.";
        };
    }

    private String resolveConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? ChatMemory.DEFAULT_CONVERSATION_ID
                : conversationId;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String normalizeStrategy(String strategy) {
        return strategy == null || strategy.isBlank() ? "basic" : strategy.trim().toLowerCase();
    }

    private boolean hasMeaningfulExtractedText(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return false;
        }

        String normalized = extractedText
                .replaceAll("\\[PAGE\\s+\\d+\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized.chars().anyMatch(Character::isLetterOrDigit);
    }

    private String buildRoutingCandidateExcerpt(String text, int limit) {
        List<TypologyCandidate> candidates = documentKnowledgeBaseService.findCandidateTypologies(text, limit);
        if (candidates.isEmpty()) {
            return "Aucune typologie candidate evidente n'a ete detectee par le pre-filtrage lexical.";
        }

        return candidates.stream()
                .map(candidate -> "- " + candidate.typologyCode() + " | " + candidate.label()
                        + " | type=" + candidate.documentType()
                        + " | raison=" + candidate.reason())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("Aucune typologie candidate evidente n'a ete detectee par le pre-filtrage lexical.");
    }

    private String buildFinalPromptPreview(
            String strategy,
            String originalText,
            String excerptText,
            Map<String, Object> context) {
        if (context != null && context.containsKey(CONTEXT_KEY_FINAL_SYSTEM_PROMPT)) {
            String finalSystemPrompt = contextString(context, CONTEXT_KEY_FINAL_SYSTEM_PROMPT, "");
            String finalUserPrompt = contextString(context, CONTEXT_KEY_FINAL_USER_PROMPT,
                    promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", excerptText == null ? "" : excerptText)));
            return "[SYSTEM]\n" + finalSystemPrompt + "\n\n[USER]\n" + finalUserPrompt;
        }

        boolean usesSemanticCandidatesOnly = "rag".equals(normalizeStrategy(strategy))
            || "hybrid".equals(normalizeStrategy(strategy));
        String systemPrompt;
        if (usesSemanticCandidatesOnly && context != null && context.containsKey("RAG_SEMANTIC_CANDIDATES")) {
            systemPrompt = promptTemplateService.render("prompts/document-routing-system.st", Map.of(
            "strategy_name", normalizeStrategy(strategy),
            "knowledge_source", "hybrid".equals(normalizeStrategy(strategy))
                ? "les 3 typologies les plus proches par embedding local Ollama (nomic-embed-text)"
                : "les 3 typologies les plus proches par similarite semantique (vector store)",
                "extra_instructions", "Choisis UNIQUEMENT parmi les 3 typologies candidates ci-dessous. Si aucune ne correspond parfaitement, renvoie typologyCode=INCONNU.",
                "routing_catalog_excerpt", String.valueOf(context.get("RAG_SEMANTIC_CANDIDATES"))));
        } else {
            systemPrompt = buildSystemPromptPreview(strategy, originalText);
        }

        StringBuilder finalPrompt = new StringBuilder();
        finalPrompt.append("[SYSTEM]\n")
            .append(systemPrompt)
            .append("\n\n[USER]\n")
            .append(promptTemplateService.render("prompts/document-routing-user.st", Map.of("document_text", excerptText == null ? "" : excerptText)));

        // For non-RAG strategies, also show the injected semantic context (from QuestionAnswerAdvisor)
        if (!usesSemanticCandidatesOnly) {
            List<String> semanticContext = retrievedContextSnippets(context);
            if (!semanticContext.isEmpty()) {
                finalPrompt.append("\n\n[SEMANTIC CONTEXT FROM VECTOR STORE]\n")
                    .append(String.join("\n", semanticContext));
            }
        }

        return finalPrompt.toString();
    }

            private String buildSystemPromptPreview(String strategy, String text) {
            String normalizedStrategy = normalizeStrategy(strategy);
            return switch (normalizedStrategy) {
                case "memory" -> promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "memory",
                    "knowledge_source", "la memoire conversationnelle precedente et un mini-catalogue embarque",
                    "extra_instructions", "Utilise les informations memorisees pour conserver une typologie coherente quand le contexte utilisateur le justifie. Si aucune memoire utile n'est disponible, reste prudent.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 6)));
                case "tools" -> promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "tools",
                    "knowledge_source", "les tools du referentiel documentaire et des statistiques factices",
                    "extra_instructions", "Avant de conclure, appelle le tool de recherche de typologies si l'extrait OCR contient plusieurs interpretations possibles.",
                    "routing_catalog_excerpt", "Le catalogue complet n'est pas dans le prompt ; va le chercher via les tools."));
                case "rag" -> promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "rag",
                    "knowledge_source", "le contexte recupere depuis le vector store RAG",
                    "extra_instructions", "Choisis prioritairement parmi les typologies candidates ci-dessous, puis confirme avec le contexte RAG. Si le contexte est insuffisant, renvoie INCONNU plutot que d'inventer.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 6)));
                case "hybrid" -> promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "hybrid",
                    "knowledge_source", "les 3 typologies les plus proches par embedding local Ollama (nomic-embed-text)",
                    "extra_instructions", "Choisis UNIQUEMENT parmi les typologies candidates retournees par l'embedding local. Si le signal reste ambigu, renvoie INCONNU.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 6)));
                default -> promptTemplateService.render("prompts/document-routing-system.st", Map.of(
                    "strategy_name", "prompt-only",
                    "knowledge_source", "un extrait compact du referentiel documentaire embarque dans le prompt systeme",
                    "extra_instructions", "N'invente pas une typologie hors format T + 6 chiffres. Si le document reste ambigu, renvoie INCONNU.",
                    "routing_catalog_excerpt", buildRoutingCandidateExcerpt(text, 8)));
            };
            }

    private int contextInt(Map<String, Object> context, String key, int defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private boolean contextBoolean(Map<String, Object> context, String key) {
        if (context == null) {
            return false;
        }
        Object value = context.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String contextString(Map<String, Object> context, String key, String defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        return value instanceof String text ? text : defaultValue;
    }

    private double contextDouble(Map<String, Object> context, String key, double defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private List<String> retrievedContextSnippets(Map<String, Object> context) {
        if (context == null) {
            return List.of();
        }

        Object value = context.get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(value instanceof List<?> documents)) {
            return List.of();
        }

        return documents.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .limit(3)
                .map(document -> {
                    String fund = String.valueOf(document.getMetadata().getOrDefault("fundCode", "GLOBAL"));
                    String typology = String.valueOf(document.getMetadata().getOrDefault("typologyCode", "N/A"));
                    return "fund=" + fund + ", typology=" + typology + " -> " + preview(document.getText(), 320);
                })
                .toList();
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String truncateForLog(String input) {
        if (input == null) {
            return "null";
        }
        return input.length() <= MAX_LOG_INPUT_LENGTH
                ? input
                : input.substring(0, MAX_LOG_INPUT_LENGTH) + "...";
    }

    private record RouteExecution(
            String strategy,
            DocumentRoutingResponse routing,
            String rawResponse,
            Map<String, Object> context) {
    }

        private record RetrievalConfidence(
            int score,
            String level,
            double d1,
            double d2,
            double gap12,
            double spreadTopN,
            String majorityDocumentType,
            double majorityRatio,
            String explanation) {
        }

    private enum HybridDecisionMode {
        LOW_SKIP,
        MEDIUM_ADAPTIVE,
        HIGH_CONSTRAINED
    }
}
