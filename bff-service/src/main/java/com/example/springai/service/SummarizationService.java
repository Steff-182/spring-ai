package com.example.springai.service;

import com.example.springai.model.SummarizeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SummarizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SummarizationService.class);
    private static final int MAX_LOG_INPUT_LENGTH = 200;

    private final ChatClient.Builder chatClientBuilder;

    public SummarizationService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public SummarizeResponse summarize(String text) {
        LOGGER.info("SummarizationService.summarize input='{}'", truncateForLog(text));

        String summary = chatClientBuilder.build()
                .prompt()
                .system("Tu resumes les textes de facon concise en francais.")
                .user("Resume le texte suivant en 3 phrases maximum:\n\n" + text)
                .call()
                .content();

        return new SummarizeResponse(summary);
    }

    private String truncateForLog(String input) {
        if (input == null) {
            return "null";
        }
        return input.length() <= MAX_LOG_INPUT_LENGTH
                ? input
                : input.substring(0, MAX_LOG_INPUT_LENGTH) + "...";
    }
}
