package com.example.springai.service;

import com.example.springai.model.SentimentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SentimentAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SentimentAnalysisService.class);
    private static final int MAX_LOG_INPUT_LENGTH = 200;

    private final ChatClient.Builder chatClientBuilder;

    public SentimentAnalysisService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public SentimentResponse analyze(String text) {
        LOGGER.info("SentimentAnalysisService.analyze input='{}'", truncateForLog(text));

        String sentiment = chatClientBuilder.build()
                .prompt()
                .system("Tu classes le sentiment en un seul mot parmi POSITIVE, NEGATIVE ou NEUTRAL.")
                .user(text)
                .call()
                .content();

        return new SentimentResponse(sentiment == null ? "UNKNOWN" : sentiment.trim());
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
