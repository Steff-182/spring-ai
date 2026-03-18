package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.springai.model.SimpleResponse;
import com.example.springai.tool.DocumentKnowledgeTools;

import java.util.Map;

@Service
public class QuestionAnswerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuestionAnswerService.class);
    private static final int MAX_LOG_INPUT_LENGTH = 200;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final PromptTemplateService promptTemplateService;
    private final DocumentKnowledgeTools documentKnowledgeTools;

    public QuestionAnswerService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            PromptTemplateService promptTemplateService,
            DocumentKnowledgeTools documentKnowledgeTools) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatMemory = chatMemory;
        this.promptTemplateService = promptTemplateService;
        this.documentKnowledgeTools = documentKnowledgeTools;
    }

    public SimpleResponse answerQuestion(String question) {
        return answerQuestion(ChatMemory.DEFAULT_CONVERSATION_ID, question);
    }

    public SimpleResponse answerQuestion(String conversationId, String question) {
        LOGGER.info("QuestionAnswerService.answerQuestion input='{}'", truncateForLog(question));

        String systemPrompt = promptTemplateService.render("prompts/question-answer-system.st", Map.of());

        String answer = chatClientBuilder.clone()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(documentKnowledgeTools)
                .build()
                .prompt()
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, resolveConversationId(conversationId)))
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        LOGGER.info("QuestionAnswerService.answerQuestion output='{}'", truncateForLog(answer));
        return new SimpleResponse(answer == null ? "No answer" : answer.trim());
    }

    private String resolveConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? ChatMemory.DEFAULT_CONVERSATION_ID
                : conversationId;
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
