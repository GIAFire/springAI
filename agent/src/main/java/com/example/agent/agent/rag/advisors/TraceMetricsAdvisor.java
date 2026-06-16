package com.example.agent.agent.rag.advisors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class TraceMetricsAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TraceMetricsAdvisor.class);

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public TraceMetricsAdvisor(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /**
     * 拦截 AI 聊天调用，执行链路追踪和指标采集。
     *
     * <p>主要功能：</p>
     * <ul>
     *   <li>生成唯一的 AI 请求 ID 并关联分布式追踪 ID</li>
     *   <li>提取请求模型名称和消息数量等上下文信息</li>
     *   <li>记录调用开始和结束的日志</li>
     *   <li>采集调用时长、Token 使用量等监控指标</li>
     *   <li>处理异常情况并记录错误指标</li>
     * </ul>
     *
     * @param chatClientRequest AI 聊天客户端请求对象，包含提示词、选项和上下文
     * @param chain   顾问责任链，用于继续执行后续的处理逻辑
     * @return AI 聊天客户端响应对象，包含完整的响应数据
     */
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain chain) {

        String requestId = MDC.get("requestId");
        String tenantId = MDC.get("tenantId");
        String userId = MDC.get("userId");
        String conversationId = MDC.get("conversationId");

        /* 生成唯一标识：AI 请求 ID 和分布式追踪 ID */
        String aiRequestId = UUID.randomUUID().toString();
        String traceId = currentTraceId();

        /* 从请求中提取模型名称，如果未配置则使用 "unknown" */
        String requestModel = Optional.ofNullable(chatClientRequest.prompt().getOptions())
                .map(ChatOptions::getModel)
                .orElse("unknown");

        /* 统计对话消息数量 */
        int messageCount = chatClientRequest.prompt().getInstructions().size();

        /* 记录调用开始时间（纳秒精度） */
        long startNs = System.nanoTime();

        /* 设置 MDC 上下文并记录请求上下文，确保日志可追踪 */
        try (MDC.MDCCloseable ignored1 = MDC.putCloseable("aiRequestId", aiRequestId);
             MDC.MDCCloseable ignored2 = MDC.putCloseable("traceId", traceId)) {
            chatClientRequest.context().put("ai.requestId", aiRequestId);
            chatClientRequest.context().put("ai.traceId", traceId);

            chatClientRequest.context().put("requestId", requestId);
            chatClientRequest.context().put("tenantId", tenantId);
            chatClientRequest.context().put("userId", userId);
            chatClientRequest.context().put("conversationId", conversationId);

            log.info("AI traceMetrics started. aiRequestId={}, traceId={}, model={}, messageCount={}",
                    aiRequestId, traceId, requestModel, messageCount);

            /* 执行后续的advisor链和实际的 AI 调用 */
            ChatClientResponse response = chain.nextCall(chatClientRequest);

            /* 计算调用耗时 */
            Duration cost = Duration.ofNanos(System.nanoTime() - startNs);

            /* 从响应中提取元数据和使用量信息 */
            ChatResponse chatResponse = response.chatResponse();
            ChatResponseMetadata metadata = chatResponse != null ? chatResponse.getMetadata() : null;
            Usage usage = metadata != null ? metadata.getUsage() : null;

            /* 优先使用响应中的模型名称，降级使用请求中的模型名称 */
            String responseModel = metadata != null && metadata.getModel() != null ? metadata.getModel() : requestModel;

            /* 提取 Token 使用量：提示词、补全和总计 */
            Integer promptTokens = usage != null ? usage.getPromptTokens() : null;
            Integer completionTokens = usage != null ? usage.getCompletionTokens() : null;
            Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

            /* 记录成功调用的监控指标：时长和 Token 使用量 */
            recordDuration(responseModel, "success", cost);
            recordToken(responseModel, "prompt", promptTokens);
            recordToken(responseModel, "completion", completionTokens);
            recordToken(responseModel, "total", totalTokens);

            log.info(
                    "AI traceMetrics succeeded. aiRequestId={}, traceId={}, model={}, costMs={}, promptTokens={}, completionTokens={}, totalTokens={}",
                    aiRequestId,
                    traceId,
                    responseModel,
                    cost.toMillis(),
                    promptTokens,
                    completionTokens,
                    totalTokens
            );

            return response;
        }
        catch (RuntimeException ex) {
            /* 计算失败场景的耗时 */
            Duration cost = Duration.ofNanos(System.nanoTime() - startNs);

            /* 记录失败调用的时长指标 */
            recordDuration(requestModel, "error", cost);

            /* 记录错误计数器指标，按异常类型分类 */
            Counter.builder("ai.chat.errors")
                    .description("AI chat call errors")
                    .tag("advisor", getName())
                    .tag("model", safeTag(requestModel))
                    .tag("exception", ex.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();

            log.warn("AI traceMetrics failed. aiRequestId={}, traceId={}, model={}, costMs={}, exception={}",
                    aiRequestId,
                    traceId,
                    requestModel,
                    cost.toMillis(),
                    ex.getClass().getName(),
                    ex);

            throw ex;
        }
    }

    /**
     * 拦截 AI 聊天流式调用，执行链路追踪和指标采集。
     *
     * <p>主要功能：</p>
     * <ul>
     *   <li>生成唯一的 AI 请求 ID 并关联分布式追踪 ID</li>
     *   <li>提取请求模型名称和消息数量等上下文信息</li>
     *   <li>记录流式调用开始的日志</li>
     *   <li>在流式响应完成时采集调用时长和 Token 使用量等监控指标</li>
     *   <li>处理异常情况并记录错误指标</li>
     * </ul>
     *
     * @param chatClientRequest   AI 聊天客户端请求对象，包含提示词、选项和上下文
     * @param streamAdvisorChain  流式顾问责任链，用于继续执行后续的处理逻辑
     * @return 经过包装的 Flux 响应流，包含追踪和指标采集逻辑
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        String requestId = MDC.get("requestId");
        String tenantId = MDC.get("tenantId");
        String userId = MDC.get("userId");
        String conversationId = MDC.get("conversationId");

        /* 生成唯一标识：AI 请求 ID 和分布式追踪 ID */
        String aiRequestId = UUID.randomUUID().toString();
        String traceId = currentTraceId();

        /* 从请求中提取模型名称，如果未配置则使用 "unknown" */
        String requestModel = Optional.ofNullable(chatClientRequest.prompt().getOptions())
                .map(ChatOptions::getModel)
                .orElse("unknown");

        /* 统计对话消息数量 */
        int messageCount = chatClientRequest.prompt().getInstructions().size();

        /* 记录调用开始时间（纳秒精度） */
        long startNs = System.nanoTime();

        /* 设置 MDC 上下文并记录请求上下文，确保日志可追踪 */
        try (MDC.MDCCloseable ignored1 = MDC.putCloseable("aiRequestId", aiRequestId);
             MDC.MDCCloseable ignored2 = MDC.putCloseable("traceId", traceId)) {
            chatClientRequest.context().put("ai.requestId", aiRequestId);
            chatClientRequest.context().put("ai.traceId", traceId);

            chatClientRequest.context().put("requestId", requestId);
            chatClientRequest.context().put("tenantId", tenantId);
            chatClientRequest.context().put("userId", userId);
            chatClientRequest.context().put("conversationId", conversationId);

            log.info("AI traceMetrics stream started. aiRequestId={}, traceId={}, model={}, messageCount={}",
                    aiRequestId, traceId, requestModel, messageCount);

            /* 执行后续的advisor链和实际的 AI 流式调用 */
            Flux<ChatClientResponse> responseFlux = streamAdvisorChain.nextStream(chatClientRequest);

            /* 在流式响应上添加指标采集逻辑 */
            return responseFlux
                    .doOnComplete(() -> {
                        /* 计算调用耗时 */
                        Duration cost = Duration.ofNanos(System.nanoTime() - startNs);

                        /* 记录成功调用的时长指标 */
                        recordDuration(requestModel, "success", cost);

                        log.info("AI traceMetrics stream completed. aiRequestId={}, traceId={}, model={}, costMs={}",
                                aiRequestId, traceId, requestModel, cost.toMillis());
                    })
                    .doOnNext(response -> {
                        /* 从最后一个流式响应块中提取 Token 使用量 */
                        ChatResponse chatResponse = response.chatResponse();
                        if (chatResponse == null)
                            return;

                        ChatResponseMetadata metadata = chatResponse.getMetadata();
                        if (metadata == null)
                            return;

                        Usage usage = metadata.getUsage();
                        if (usage == null)
                            return;

                        /* 优先使用响应中的模型名称，降级使用请求中的模型名称 */
                        String responseModel = metadata.getModel() != null ? metadata.getModel() : requestModel;

                        /* 提取 Token 使用量：提示词、补全和总计 */
                        Integer promptTokens = usage.getPromptTokens();
                        Integer completionTokens = usage.getCompletionTokens();
                        Integer totalTokens = usage.getTotalTokens();

                        /* 记录 Token 使用量指标（只在最后一次响应时有值） */
                        recordToken(responseModel, "prompt", promptTokens);
                        recordToken(responseModel, "completion", completionTokens);
                        recordToken(responseModel, "total", totalTokens);

                        /* 如果有 Token 数据，记录详细日志 */
                        if (totalTokens != null && totalTokens > 0) {
                            log.info("AI traceMetrics stream token usage. aiRequestId={}, traceId={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                                    aiRequestId, traceId, responseModel, promptTokens, completionTokens, totalTokens);
                        }
                    })
                    .doOnError(error -> {
                        /* 计算失败场景的耗时 */
                        Duration cost = Duration.ofNanos(System.nanoTime() - startNs);

                        /* 记录失败调用的时长指标 */
                        recordDuration(requestModel, "error", cost);

                        /* 记录错误计数器指标，按异常类型分类 */
                        Counter.builder("ai.chat.errors")
                                .description("AI chat call errors")
                                .tag("advisor", getName())
                                .tag("model", safeTag(requestModel))
                                .tag("exception", error.getClass().getSimpleName())
                                .register(meterRegistry)
                                .increment();

                        log.warn("AI traceMetrics stream failed. aiRequestId={}, traceId={}, model={}, costMs={}, exception={}",
                                aiRequestId, traceId, requestModel, cost.toMillis(), error.getClass().getName(), error);
                    });
        }
    }

    /**
     * 获取当前分布式追踪的 Trace ID。
     *
     * <p>如果 Tracer 未配置或当前没有活跃的 Span，则返回 "no-trace"。</p>
     *
     * @return 当前追踪上下文的 Trace ID，如果不可用则返回 "no-trace"
     */
    private String currentTraceId() {
        if (tracer == null) {
            return "no-trace";
        }

        Span span = tracer.currentSpan();
        if (span == null) {
            return "no-trace";
        }

        return span.context().traceId();
    }

    /**
     * 记录 AI 聊天调用时长的监控指标。
     *
     * @param model    AI 模型名称，用于指标分组
     * @param status   调用状态："success" 或 "error"
     * @param duration 调用耗时
     */
    private void recordDuration(String model, String status, Duration duration) {
        Timer.builder("ai.chat.call.duration")
                .description("AI chat call duration measured by custom advisor")
                .tag("advisor", getName())
                .tag("model", safeTag(model))
                .tag("status", status)
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录 AI 聊天 Token 使用量的监控指标。
     *
     * <p>如果 Token 值为 null 或小于等于 0，则跳过记录。</p>
     *
     * @param model AI 模型名称，用于指标分组
     * @param type  Token 类型："prompt"、"completion" 或 "total"
     * @param value Token 数量
     */
    private void recordToken(String model, String type, Integer value) {
        if (value == null || value <= 0) {
            return;
        }

        Counter.builder("ai.chat.tokens")
                .description("AI chat token usage measured by custom advisor")
                .tag("advisor", getName())
                .tag("model", safeTag(model))
                .tag("type", type)
                .register(meterRegistry)
                .increment(value);
    }

    /**
     * 安全地转换标签值，避免 null 或空字符串导致监控指标异常。
     *
     * @param value 原始标签值
     * @return 如果值为 null 或空白则返回 "unknown"，否则返回原值
     */
    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * 获取此顾问的名称标识，用于监控指标的标签区分。
     *
     * @return 顾问名称 "ai-trace-metrics-advisor"
     */
    @Override
    public String getName() {
        return "ai-trace-metrics-advisor";
    }

    /**
     * 获取此顾问的执行优先级顺序。
     *
     * <p>设置为最高优先级 + 100，确保在其他低优先级顾问之前执行。</p>
     *
     * @return 优先级顺序值
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }



}
