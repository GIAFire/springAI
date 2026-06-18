package com.example.agent.agent.config;

import com.example.agent.agent.config.tool.ToolSearchAdvisorFactory;
import com.example.agent.agent.rag.advisors.AuditAdvisor;
import com.example.agent.agent.rag.advisors.TraceMetricsAdvisor;
import com.example.agent.agent.tools.MallAdminRoleTools;
import com.example.agent.agent.tools.MallAdminUserTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Configuration
public class ChatClientConfig {
    @Autowired
    private TraceMetricsAdvisor traceMetricsAdvisor;
    @Autowired
    private AuditAdvisor auditAdvisor;
    @Autowired
    private ChatMemory chatMemory;
    @Autowired
    private SafeGuardAdvisor safeGuardAdvisor;
    private ChatClient.Builder baseAgentBuilder(ChatClient.Builder builder) {
        // 设置上下文最大记录
//        MessageWindowChatMemory messageBuild = MessageWindowChatMemory.builder().maxMessages(1000).build();
        return builder.clone()
                // !!!最重要 请求拦截器,每次请求时从外部获取数据,添加到上下文.如知识库,搜索结果,会话记忆等
                .defaultAdvisors(traceMetricsAdvisor)
//                .defaultAdvisors(safeGuardAdvisor)
                .defaultAdvisors(auditAdvisor)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    @Bean
    public ChatClient userAgentClient(ChatClient.Builder builder,
                                      MallAdminUserTools mallAdminUserTools,
                                      ToolSearchAdvisorFactory toolSearchAdvisorFactory,
                                      @Value("${app.ai.agents.user.tool-set-id:user-agent-tools}") String toolSetId) {

        // 从baseAgentBuilder中获取基础配置
        ChatClient agent = baseAgentBuilder(builder)
                // 重要 默认关键词,可根据业务进行读取配置文件变量定义模板
                .defaultSystem("你是一个userAgent")
                // 重要 适合固定功能性智能体,在用户提问时,额外补充内容,相当于帮用户补充、优化提问内容
                .defaultUser(user -> user
                        .text("{level}")
                        .param("level", ""))
                // 每个 Agent 挂自己的 ToolSearchAdvisor，并传入独立 toolSetId。
                // userAgent 只能从 user-agent-tools 这套工具索引里检索工具。
                .defaultAdvisors(toolSearchAdvisorFactory.create(toolSetId))
                // 设置模型参数
                .defaultOptions(ChatOptions.builder()
                        // 小稳定,大热情
                        .temperature(0.9))
                // 重要 设置默认工具
                .defaultTools(mallAdminUserTools)
                // 用于给工具传默认参数
                .defaultToolContext(Map.of(
                        "tenantId", "00001"
                )).build();

        return agent;
    }

    @Bean
    public ChatClient orderAgentClient(ChatClient.Builder builder,
                                      MallAdminRoleTools mallAdminRoleTools,
                                      ToolSearchAdvisorFactory toolSearchAdvisorFactory,
                                      @Value("${app.ai.agents.order.tool-set-id:order-agent-tools}") String toolSetId) {

        // 从baseAgentBuilder中获取基础配置
        ChatClient agent = baseAgentBuilder(builder)
                // 重要 默认关键词,可根据业务进行读取配置文件变量定义模板
                .defaultSystem("你是一个orderAgent")
                // 重要 适合固定功能性智能体,在用户提问时,额外补充内容,相当于帮用户补充、优化提问内容
                .defaultUser(user -> user
                        .text("{level}")
                        .param("level", ""))
                // 每个 Agent 挂自己的 ToolSearchAdvisor，并传入独立 toolSetId。
                // 这里暂时使用 MallAdminRoleTools 演示隔离；后续换成订单工具即可。
                .defaultAdvisors(toolSearchAdvisorFactory.create(toolSetId), loggerAdvisor)
                // 设置模型参数
                .defaultOptions(ChatOptions.builder()
                        // 小稳定,大热情
                        .temperature(0.1))
                // 重要 设置默认工具
                .defaultTools(mallAdminRoleTools)
                // 用于给工具传默认参数
                .defaultToolContext(Map.of(
                        "tenantId", "00001"
                )).build();

        return agent;
    }

    private SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor(
            request -> {
                var prompt = request.prompt();

                String messages = prompt.getInstructions().stream()
                        .map(ChatClientConfig::formatMessage)
                        .collect(Collectors.joining("\n\n"));

                String tools = formatTools(prompt.getOptions());

                return """
                        ========= Spring AI Request =========
                        OPTIONS:
                        %s

                        MESSAGES:
                        %s

                        TOOLS:
                        %s
                        =====================================
                        """.formatted(
                        formatOptions(prompt.getOptions()),
                        messages,
                        tools
                );
            },
            response -> """
                    ========= Spring AI Response =========
                    %s
                    ======================================
                    """.formatted(response),
            Ordered.LOWEST_PRECEDENCE - 100
    );


    private static String formatMessage(Message message) {
        if (message instanceof ToolResponseMessage toolMessage) {
            return formatToolResponseMessage(toolMessage);
        }

        if (message instanceof AssistantMessage assistantMessage) {
            return formatAssistantMessage(assistantMessage);
        }

        return """
            role: %s
            content:
            %s
            metadata: %s
            """.formatted(
                message.getMessageType(),
                nullToEmpty(message.getText()),
                message.getMetadata()
        );
    }

    private static String formatAssistantMessage(AssistantMessage message) {
        String toolCalls = "";

        if (message.hasToolCalls()) {
            toolCalls = message.getToolCalls().stream()
                    .map(toolCall -> """
                        toolCallId: %s
                        type: %s
                        name: %s
                        arguments:
                        %s
                        """.formatted(
                            toolCall.id(),
                            toolCall.type(),
                            toolCall.name(),
                            toolCall.arguments()
                    ))
                    .collect(Collectors.joining("\n"));
        }

        return """
            role: %s
            content:
            %s
            toolCalls:
            %s
            metadata: %s
            """.formatted(
                message.getMessageType(),
                nullToEmpty(message.getText()),
                toolCalls.isBlank() ? "(none)" : toolCalls,
                message.getMetadata()
        );
    }

    private static String formatToolResponseMessage(ToolResponseMessage message) {
        String responses = Optional.ofNullable(message.getResponses())
                .orElseGet(List::of)
                .stream()
                .map(response -> """
                    toolCallId: %s
                    toolName: %s
                    responseData:
                    %s
                    """.formatted(
                        response.id(),
                        response.name(),
                        response.responseData()
                ))
                .collect(Collectors.joining("\n"));

        return """
            role: %s
            toolResponses:
            %s
            metadata: %s
            """.formatted(
                message.getMessageType(),
                responses.isBlank() ? "(empty)" : responses,
                message.getMetadata()
        );
    }

    private static String formatTools(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return "(options is not ToolCallingChatOptions)";
        }

        return Optional.ofNullable(toolOptions.getToolCallbacks())
                .orElseGet(List::of)
                .stream()
                .map(ToolCallback::getToolDefinition)
                .map(ChatClientConfig::formatToolDefinition)
                .collect(Collectors.joining("\n"));
    }

    private static String formatToolDefinition(ToolDefinition definition) {
        return """
            name: %s
            description:
            %s
            inputSchema:
            %s
            """.formatted(
                definition.name(),
                definition.description(),
                definition.inputSchema()
        );
    }

    private static String formatOptions(ChatOptions options) {
        if (options == null) {
            return "(null)";
        }

        return """
            class: %s
            model: %s
            temperature: %s
            maxTokens: %s
            topP: %s
            """.formatted(
                options.getClass().getName(),
                options.getModel(),
                options.getTemperature(),
                options.getMaxTokens(),
                options.getTopP()
        );
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
