package com.example.springai.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.springai.model.PdfPagePreview;

@Service
public class PdfDocumentReaderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfDocumentReaderService.class);
    private static final int PAGE_PREVIEW_LENGTH = 450;
    private static final int OCR_DPI = 300;
    private static final int OCR_MAX_PAGES = 2;

    private final boolean tesseractEnabled;
    private final String tesseractLanguage;
    private final String tesseractDataPath;

    public PdfDocumentReaderService(
            @Value("${app.ocr.tesseract.enabled:true}") boolean tesseractEnabled,
            @Value("${app.ocr.tesseract.language:fra}") String tesseractLanguage,
            @Value("${app.ocr.tesseract.data-path:}") String tesseractDataPath) {
        this.tesseractEnabled = tesseractEnabled;
        this.tesseractLanguage = tesseractLanguage;
        this.tesseractDataPath = tesseractDataPath;
    }

    public PdfExtractionResult extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Aucun fichier PDF fourni.");
        }

        String fileName = file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename();

        try {
            byte[] pdfBytes = file.getBytes();

            Resource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };

            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .withPageTopMargin(0)
                    .withPageBottomMargin(0)
                    .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                            .withLeftAlignment(true)
                            .overrideLineSeparator("\n")
                            .build())
                    .build();

            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            List<Document> pageDocuments = reader.get();

            String pdfText = pageDocuments.stream()
                    .map(pageDocument -> "[PAGE " + pageNumber(pageDocument) + "]\n" + safeText(pageDocument))
                    .collect(Collectors.joining("\n\n"));

            List<PdfPagePreview> pagePreviews = new ArrayList<>(pageDocuments.stream()
                    .map(pageDocument -> new PdfPagePreview(
                            pageNumber(pageDocument),
                            safeText(pageDocument).length(),
                            preview(safeText(pageDocument), PAGE_PREVIEW_LENGTH)))
                    .toList());

            boolean hasPdfText = hasMeaningfulText(pdfText);
            if (hasPdfText) {
                LOGGER.info("[BFF][OCR][STEP1] mode=PDF_TEXT_LAYER ocrApplied=false chars={} text=\n{}",
                    pdfText.length(),
                    pdfText);
                String readerExplanation = "Detection couche texte: OK. PagePdfDocumentReader a extrait du texte natif du PDF (sans OCR). Ce texte est ensuite envoye dans la chaine d'advisors avant routage.";
                return new PdfExtractionResult(
                        fileName,
                        pageDocuments.size(),
                        pdfText,
                        pagePreviews,
                        readerExplanation,
                        "PDF_TEXT_LAYER",
                        false,
                        false,
                        "Texte natif detecte dans le PDF ; OCR non necessaire.");
            }

            OcrResult ocrResult = extractFirstPagesWithTesseract(pdfBytes, OCR_MAX_PAGES);
            logOcrPageResults(ocrResult.pageResults());
            if (ocrResult.success() && hasMeaningfulText(ocrResult.text())) {
                LOGGER.info("[BFF][OCR][STEP1] mode=OCR_FIRST_TWO_PAGES ocrApplied=true chars={}",
                        ocrResult.text().length());
                String ocrText = ocrResult.text();
                if (pagePreviews.stream().noneMatch(page -> page.characterCount() > 0)) {
                    ocrResult.pageResults().forEach(page -> pagePreviews.add(
                            new PdfPagePreview(page.pageNumber(), page.text().length(), preview(page.text(), PAGE_PREVIEW_LENGTH))));
                }

                String readerExplanation = "Detection couche texte: ECHEC. Fallback OCR active sur les deux premieres pages via Tess4J, puis envoi de cet extrait OCR au pipeline advisors/LLM.";
                return new PdfExtractionResult(
                        fileName,
                        pageDocuments.size(),
                        ocrText,
                        pagePreviews,
                        readerExplanation,
                        "OCR_FIRST_TWO_PAGES",
                        true,
                        true,
                        ocrResult.diagnostic());
            }

        LOGGER.info("[BFF][OCR][STEP1] mode=NO_TEXT ocrApplied=true diagnostic={}", ocrResult.diagnostic());
        String readerExplanation = "Detection couche texte: ECHEC. Tentative OCR sur les deux premieres pages effectuee mais sans texte exploitable. Le backend doit stopper la classification pour eviter un faux positif base uniquement sur le referentiel.";
            return new PdfExtractionResult(
                    fileName,
                    pageDocuments.size(),
                    "",
                    pagePreviews,
                    readerExplanation,
                    "NO_TEXT",
                    true,
                    false,
                    ocrResult.diagnostic());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Impossible de lire le PDF fourni.", exception);
        }
    }

    private OcrResult extractFirstPagesWithTesseract(byte[] pdfBytes, int maxPages) {
        if (!tesseractEnabled) {
            return new OcrResult("", false, "OCR desactive par configuration (app.ocr.tesseract.enabled=false).", List.of());
        }

        try (PDDocument pdDocument = Loader.loadPDF(pdfBytes)) {
            if (pdDocument.getNumberOfPages() == 0) {
                return new OcrResult("", false, "PDF vide: aucune page a passer en OCR.", List.of());
            }

            PDFRenderer renderer = new PDFRenderer(pdDocument);

            Tesseract tesseract = new Tesseract();
            if (tesseractDataPath != null && !tesseractDataPath.isBlank()) {
                tesseract.setDatapath(tesseractDataPath);
            }
            tesseract.setLanguage(tesseractLanguage);

            int pageCount = Math.min(pdDocument.getNumberOfPages(), Math.max(1, maxPages));
            List<OcrPageResult> pageResults = new ArrayList<>();
            for (int index = 0; index < pageCount; index++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(index, OCR_DPI);
                String text = tesseract.doOCR(pageImage);
                String normalizedText = text == null ? "" : text.trim();
                pageResults.add(new OcrPageResult(index + 1, normalizedText));
            }

            String combinedText = pageResults.stream()
                    .filter(page -> page.text() != null && !page.text().isBlank())
                    .map(page -> "[OCR PAGE " + page.pageNumber() + "]\n" + page.text())
                    .collect(Collectors.joining("\n\n"));

            boolean success = pageResults.stream().anyMatch(page -> hasMeaningfulText(page.text()));
            return new OcrResult(
                    combinedText,
                    success,
                    "OCR Tess4J termine sur " + pageCount + " page(s) (lang=" + tesseractLanguage + ").",
                    pageResults);
        }
        catch (IOException exception) {
            return new OcrResult("", false, "OCR impossible: echec de rendu PDF pour OCR (" + exception.getMessage() + ").", List.of());
        }
        catch (TesseractException exception) {
            return new OcrResult("", false,
                    "OCR impossible: verifier installation Tesseract/tessdata (" + exception.getMessage() + ").",
                    List.of());
        }
    }

    private void logOcrPageResults(List<OcrPageResult> pageResults) {
        if (pageResults == null || pageResults.isEmpty()) {
            return;
        }

        for (OcrPageResult pageResult : pageResults) {
            LOGGER.info("[BFF][OCR] page={} chars={} text=\n{}",
                    pageResult.pageNumber(),
                    pageResult.text() == null ? 0 : pageResult.text().length(),
                    pageResult.text() == null ? "" : pageResult.text());
        }
    }

    private int pageNumber(Document pageDocument) {
        Object value = pageDocument.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 1;
    }

    private String safeText(Document pageDocument) {
        return pageDocument.getText() == null ? "" : pageDocument.getText();
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private boolean hasMeaningfulText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = value
                .replaceAll("\\[PAGE\\s+\\d+\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized.chars().anyMatch(Character::isLetterOrDigit);
    }

    public record PdfExtractionResult(
            String fileName,
            int pageCount,
            String combinedText,
            List<PdfPagePreview> pagePreviews,
            String readerExplanation,
            String extractionMode,
            boolean ocrAttempted,
            boolean ocrSucceeded,
            String extractionDiagnostic) {
    }

    private record OcrResult(
            String text,
            boolean success,
            String diagnostic,
            List<OcrPageResult> pageResults) {
        }

        private record OcrPageResult(
            int pageNumber,
            String text) {
    }
}