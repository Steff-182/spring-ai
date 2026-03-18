package com.example.embedding.controller;

import com.example.embedding.dto.*;
import com.example.embedding.service.TypologyLoaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/embed")
@CrossOrigin(origins = "*")
@Tag(name = "Embedding", description = "Ingestion et recherche sémantique via Ollama + PgVector")
public class EmbeddingController {

        private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingController.class);
        private static final Pattern KIND_FILTER_PATTERN = Pattern.compile("kind\\s*==\\s*'([^']+)'");

    private final VectorStore vectorStore;
    private final TypologyLoaderService typologyLoaderService;
        private final EmbeddingModel embeddingModel;
        private final JdbcTemplate jdbcTemplate;
        private final String vectorTable;

        public EmbeddingController(
                        VectorStore vectorStore,
                        TypologyLoaderService typologyLoaderService,
                        EmbeddingModel embeddingModel,
                        JdbcTemplate jdbcTemplate,
                        @Value("${app.embedding.vector-table:vector_store_embedding}") String vectorTable) {
        this.vectorStore = vectorStore;
        this.typologyLoaderService = typologyLoaderService;
                this.embeddingModel = embeddingModel;
                this.jdbcTemplate = jdbcTemplate;
                this.vectorTable = vectorTable;
    }

    // ─── Ingestion ───────────────────────────────────────────────────────────

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Ingérer un document",
            description = "Vectorise le contenu via Ollama (nomic-embed-text) et le stocke dans PgVector.")
    public IngestResponse ingest(@RequestBody IngestRequest request) {
        Map<String, Object> metadata = request.metadata() != null ? request.metadata() : Map.of();
        Document doc = new Document(request.content(), metadata);
        vectorStore.add(List.of(doc));
        return new IngestResponse(doc.getId(), "Document indexé avec succès");
    }

    // ─── Recherche sémantique ─────────────────────────────────────────────────

    @PostMapping("/search")
    @Operation(
            summary = "Recherche sémantique",
            description = """
                    Vectorise la requête via Ollama, puis retourne les top-k documents
                    les plus similaires (distance cosinus) depuis PgVector.
                    Le champ `filterExpression` est optionnel, ex: `kind == 'typology'`.
                    """)
    public List<SimilarDocumentResponse> search(@RequestBody QueryRequest request) {
        int topK = request.topK() > 0 ? request.topK() : 3;
        LOGGER.info("[EMBED][SEARCH] topK={} filter={} queryPreview='{}'", topK, request.filterExpression(), preview(request.query(), 500));

        float[] queryVector = embeddingModel.embed(request.query());
        LOGGER.info("[EMBED][ONNX_VECTOR] dimensions={} values={}", queryVector.length, Arrays.toString(queryVector));
        logCosineDistancesFromPg(queryVector, request.filterExpression(), topK);

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.query())
                .topK(topK);

        if (request.filterExpression() != null && !request.filterExpression().isBlank()) {
            builder.filterExpression(
                    new FilterExpressionTextParser().parse(request.filterExpression()));
        }

        List<SimilarDocumentResponse> response = vectorStore.similaritySearch(builder.build())
                .stream()
                .map(doc -> new SimilarDocumentResponse(
                        doc.getId(),
                        doc.getFormattedContent(),
                        doc.getMetadata(),
                        doc.getScore() != null ? doc.getScore() : 0.0))
                .toList();

        RetrievalConfidence confidence = buildRetrievalConfidence(response);
        LOGGER.info("[EMBED][CONFIDENCE] topN={} score={} level={} -> d1={} d2={} gap12={} spread={} majorityType={} majorityRatio={}",
                response.size(),
                confidence.score(),
                confidence.level(),
                confidence.d1(),
                confidence.d2(),
                confidence.gap12(),
                confidence.spreadTopN(),
                confidence.majorityDocumentType(),
                confidence.majorityRatio());
        LOGGER.info("[EMBED][CONFIDENCE][BEGINNER_EXPLANATION] {}", confidence.explanation());

        for (int i = 0; i < response.size(); i++) {
            SimilarDocumentResponse item = response.get(i);
            LOGGER.info("[EMBED][TOPK] rank={} id={} typology={} cosineDistance={} metadata={}",
                    i + 1,
                    item.id(),
                    item.metadata().getOrDefault("typologyCode", "N/A"),
                    item.score(),
                    item.metadata());
        }
        return response;
    }

    // ─── Gestion des typologies ───────────────────────────────────────────────

    @PostMapping("/typologies/reload")
    @Operation(
            summary = "Recharger les typologies",
            description = "Force le rechargement de toutes les typologies depuis le catalogue JSON (supprime et réinsère).")
    public TypologyReloadResponse reloadTypologies() {
        int count = typologyLoaderService.reloadTypologies();
        return new TypologyReloadResponse(count,
                count + " typologies rechargées depuis document-routing-catalog.json");
    }

    @GetMapping("/typologies/count")
    @Operation(
            summary = "Compter les typologies indexées",
            description = "Retourne le nombre de documents de type 'typology' présents dans PgVector.")
    public Map<String, Integer> countTypologies() {
        return Map.of("count", typologyLoaderService.countTypologies());
    }

        private void logCosineDistancesFromPg(float[] queryVector, String filterExpression, int topK) {
                String vectorLiteral = toPgVectorLiteral(queryVector);
                String kindFilter = extractKindFilter(filterExpression);

                StringBuilder sql = new StringBuilder(
                                "SELECT id, metadata, content, embedding <=> CAST(? AS vector) AS cosine_distance FROM " + vectorTable);
                List<Object> params = new ArrayList<>();
                params.add(vectorLiteral);

                if (kindFilter != null) {
                        sql.append(" WHERE metadata->>'kind' = ?");
                        params.add(kindFilter);
                }

                sql.append(" ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?");
                params.add(vectorLiteral);
                params.add(topK);

                List<CosineDistanceRow> rows = jdbcTemplate.query(
                                sql.toString(),
                                (rs, rowNum) -> new CosineDistanceRow(
                                                rs.getString("id"),
                                                rs.getString("metadata"),
                                                rs.getString("content"),
                                                rs.getDouble("cosine_distance")),
                                params.toArray());

                if (rows.isEmpty()) {
                        LOGGER.info("[EMBED][PG_COSINE] no row found in table={} for filter={}", vectorTable, filterExpression);
                        return;
                }

                for (int i = 0; i < rows.size(); i++) {
                        CosineDistanceRow row = rows.get(i);
                        LOGGER.info("[EMBED][PG_COSINE] rank={} id={} cosineDistance={} metadata={} contentPreview='{}'",
                                        i + 1,
                                        row.id(),
                                        row.cosineDistance(),
                                        row.metadataJson(),
                                        preview(row.content(), 220));
                }
        }

        private String extractKindFilter(String filterExpression) {
                if (filterExpression == null || filterExpression.isBlank()) {
                        return null;
                }

                Matcher matcher = KIND_FILTER_PATTERN.matcher(filterExpression.trim());
                if (!matcher.matches()) {
                        return null;
                }
                return matcher.group(1);
        }

        private String toPgVectorLiteral(float[] vector) {
                StringBuilder builder = new StringBuilder("[");
                for (int i = 0; i < vector.length; i++) {
                        if (i > 0) {
                                builder.append(',');
                        }
                        builder.append(vector[i]);
                }
                builder.append(']');
                return builder.toString();
        }

        private String preview(String value, int maxLength) {
                if (value == null) {
                        return "null";
                }
                return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
        }

        private RetrievalConfidence buildRetrievalConfidence(List<SimilarDocumentResponse> candidatesTopN) {
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
                                .map(doc -> String.valueOf(doc.metadata() == null ? "N/A" : doc.metadata().getOrDefault("documentType", "N/A")))
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

                LOGGER.info("[EMBED][CONFIDENCE][COMPONENTS] orientation={} gapRaw={} gapScore={} weightedGap={} spreadRaw={} spreadScore={} weightedSpread={} majorityRaw={} majorityScore={} weightedMajority={}",
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

        private double safeDistance(List<SimilarDocumentResponse> values, int index) {
                return safeDistance(values, index, 1.0);
        }

        private double safeDistance(List<SimilarDocumentResponse> values, int index, double defaultValue) {
                if (values == null || values.isEmpty()) {
                        return defaultValue;
                }
                int safeIndex = Math.min(Math.max(index, 0), values.size() - 1);
                SimilarDocumentResponse value = values.get(safeIndex);
                return value == null ? defaultValue : value.score();
        }

        private double clamp(double value) {
                return Math.max(0.0, Math.min(1.0, value));
        }

        private record CosineDistanceRow(
                        String id,
                        String metadataJson,
                        String content,
                        double cosineDistance) {
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
}
