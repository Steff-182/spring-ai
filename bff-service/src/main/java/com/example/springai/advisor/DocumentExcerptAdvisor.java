package com.example.springai.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;

public class DocumentExcerptAdvisor implements BaseAdvisor {

    private static final String OCR_SECTION_MARKER = "Extrait OCR:";
    private static final String CONTEXT_SECTION_MARKER = "Context information is below";

    public static final String ORIGINAL_LENGTH = "documentExcerptAdvisor.originalLength";
    public static final String EXCERPT_LENGTH = "documentExcerptAdvisor.excerptLength";
    public static final String WAS_TRUNCATED = "documentExcerptAdvisor.wasTruncated";
    public static final String EXCERPT_TEXT = "documentExcerptAdvisor.excerptText";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentExcerptAdvisor.class);

    private final int maxCharacters;
    private final int headCharacters;
    private final int tailCharacters;
    private final int order;

    public DocumentExcerptAdvisor(int maxCharacters, int headCharacters, int tailCharacters, int order) {
        this.maxCharacters = maxCharacters;
        this.headCharacters = headCharacters;
        this.tailCharacters = tailCharacters;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        if (chatClientRequest.prompt().getUserMessage() == null) {
            return chatClientRequest;
        }

        String original = chatClientRequest.prompt().getUserMessage().getText();
        String excerpt = excerpt(original);
        boolean truncated = !excerpt.equals(original);

        if (truncated) {
            LOGGER.info("DocumentExcerptAdvisor trimmed OCR input from {} to {} characters", original.length(), excerpt.length());
        }

        Prompt updatedPrompt = truncated ? chatClientRequest.prompt().augmentUserMessage(excerpt) : chatClientRequest.prompt();

        return chatClientRequest.mutate()
                .prompt(updatedPrompt)
                .context(ORIGINAL_LENGTH, original.length())
                .context(EXCERPT_LENGTH, excerpt.length())
                .context(WAS_TRUNCATED, truncated)
            .context(EXCERPT_TEXT, excerpt)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public String createExcerpt(String input) {
        return excerpt(input);
    }

    private String excerpt(String input) {
        String sectionAwareExcerpt = excerptOcrSectionOnly(input);
        if (sectionAwareExcerpt != null) {
            return sectionAwareExcerpt;
        }

        if (input == null || input.length() <= maxCharacters) {
            return input;
        }

        int safeHead = Math.min(headCharacters, input.length());
        int safeTail = Math.min(tailCharacters, Math.max(0, input.length() - safeHead));

        if (safeHead + safeTail >= input.length()) {
            return input;
        }

        return input.substring(0, safeHead)
                + "\n\n[... extrait OCR tronque pour limiter les tokens ; conserver premiere page, clauses structurantes et signatures ...]\n\n"
                + input.substring(input.length() - safeTail);
    }

    private String excerptOcrSectionOnly(String input) {
        if (input == null) {
            return null;
        }

        int ocrStart = input.indexOf(OCR_SECTION_MARKER);
        if (ocrStart < 0) {
            return null;
        }

        int ocrContentStart = ocrStart + OCR_SECTION_MARKER.length();
        while (ocrContentStart < input.length()) {
            char current = input.charAt(ocrContentStart);
            if (current != '\r' && current != '\n') {
                break;
            }
            ocrContentStart++;
        }

        int contextStart = input.indexOf(CONTEXT_SECTION_MARKER, ocrContentStart);
        String ocrContent = contextStart >= 0
                ? input.substring(ocrContentStart, contextStart)
                : input.substring(ocrContentStart);

        if (ocrContent.length() <= maxCharacters) {
            return input;
        }

        int safeHead = Math.min(headCharacters, ocrContent.length());
        int safeTail = Math.min(tailCharacters, Math.max(0, ocrContent.length() - safeHead));
        if (safeHead + safeTail >= ocrContent.length()) {
            return input;
        }

        String trimmedOcr = ocrContent.substring(0, safeHead)
                + "\n\n[... extrait OCR tronque pour limiter les tokens ; conserver premiere page, clauses structurantes et signatures ...]\n\n"
                + ocrContent.substring(ocrContent.length() - safeTail);

        if (contextStart >= 0) {
            return input.substring(0, ocrContentStart) + trimmedOcr + input.substring(contextStart);
        }

        return input.substring(0, ocrContentStart) + trimmedOcr;
    }
}