package com.example.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        // Global role prompt used by regular chat and RAG calls unless overridden per request.
        return builder
                .defaultSystem("你是一个 Java Spring AI Agent 助手，回答要简洁、准确。")
                .build();
    }
}
