package com.example.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        // 设置上下文最大记录
        MessageWindowChatMemory messageBuild = MessageWindowChatMemory.builder().maxMessages(1000).build();
        return builder
                // 重要 默认关键词,可根据业务进行读取配置文件变量定义模板
                .defaultSystem("你是一个Agent")
                // 重要 适合固定功能性智能体,在用户提问时,额外补充内容,相当于帮用户补充、优化提问内容
                .defaultUser(user -> user
                        .text("{level}")
                        .param("level", ""))
                // !!!最重要 请求拦截器,每次请求时从外部获取数据,添加到上下文.如知识库,搜索结果,会话记忆等
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(messageBuild).build())
                // 设置模型参数
                .defaultOptions(ChatOptions.builder()
                        // 小稳定,大热情
                        .temperature(0.8))
                // 重要 设置默认工具
                .defaultTools()
                // 用于给工具传默认参数
                .defaultToolContext(Map.of(
                        "tenantId", "t001",
                        "system", "mall",
                        "env", "prod"
                ))
                .build();
    }
}
