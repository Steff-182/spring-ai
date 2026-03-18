package com.example.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class LocalChatClientConfig {

    @Bean
    ChatClient.Builder chatClientBuilder(@Qualifier("ollamaChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}