package com.example.embedding.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Charge les prototypes de typologies documentaires dans PgVector au démarrage.
 * Le chargement est idempotent : si la table contient déjà des typologies, rien n'est fait.
 */
@Service
public class TypologyLoaderService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TypologyLoaderService.class);
    private static final String CATALOG_PATH = "classpath:data/document-routing-catalog.json";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String vectorTable;

    public TypologyLoaderService(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper, ResourceLoader resourceLoader,
            @Value("${app.embedding.vector-table:vector_store_embedding}") String vectorTable) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.vectorTable = vectorTable;
    }

    @Override
    public void run(String... args) {
        int existing = countTypologies();
        if (existing > 0) {
            log.info("PgVector : {} typologie(s) déjà indexée(s), chargement ignoré.", existing);
            return;
        }
        int loaded = reloadTypologies();
        log.info("PgVector : {} typologie(s) chargée(s) depuis {}", loaded, CATALOG_PATH);
    }

    /** Force un rechargement complet (supprime + réinsère). */
    public int reloadTypologies() {
        jdbcTemplate.update("DELETE FROM " + vectorTable + " WHERE metadata->>'kind' = 'typology'");
        List<Document> docs = buildTypologyDocuments();
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
        }
        return docs.size();
    }

    public int countTypologies() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + vectorTable + " WHERE metadata->>'kind' = 'typology'",
            Integer.class);
        return count != null ? count : 0;
    }

    // ─── Construction des documents ──────────────────────────────────────────

    private List<Document> buildTypologyDocuments() {
        RoutingCatalog catalog = loadCatalog();
        List<Document> documents = new ArrayList<>();

        for (TypologyEntry t : catalog.typologies()) {
            String prototypeText = String.join("\n",
                    "Code: " + t.code(),
                    "Libellé: " + t.label(),
                    "Fonds: " + t.fundCode(),
                    "Type de document: " + t.documentType(),
                    "Description: " + t.description(),
                    "Mots-clés: " + String.join(", ", t.keywords()),
                    "Stratégie d'extraction: " + t.tokenStrategy());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("kind", "typology");
            metadata.put("typologyCode", t.code());
            metadata.put("fundCode", t.fundCode());
            metadata.put("documentType", t.documentType());
            metadata.put("label", t.label());

            documents.add(new Document(prototypeText, metadata));
        }
        return documents;
    }

    private RoutingCatalog loadCatalog() {
        try (InputStream is = resourceLoader.getResource(CATALOG_PATH).getInputStream()) {
            return objectMapper.readValue(is, RoutingCatalog.class);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de charger " + CATALOG_PATH, e);
        }
    }

    // ─── Modèle de données interne (catalogue JSON) ───────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoutingCatalog(List<FundEntry> funds, List<TypologyEntry> typologies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FundEntry(String code, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TypologyEntry(
            String code,
            String label,
            String fundCode,
            String documentType,
            String description,
            List<String> keywords,
            String tokenStrategy) {}
}
