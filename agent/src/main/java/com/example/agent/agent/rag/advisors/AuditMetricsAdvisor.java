package com.example.agent.agent.rag.advisors;

import com.example.agent.agent.entity.AiCallAuditEvent;
import com.example.agent.agent.service.CallAuditSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class AuditMetricsAdvisor implements CallAdvisor {

    private final MeterRegistry meterRegistry;
    private final CallAuditSink auditSink;

    public AuditMetricsAdvisor(
            MeterRegistry meterRegistry,
            CallAuditSink auditSink
    ) {
        this.meterRegistry = meterRegistry;
        this.auditSink = auditSink;
    }

    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest request,
            CallAdvisorChain chain
    ) {
        String requestId = safe(MDC.get("requestId"), "unknown");
        String tenantId = safe(MDC.get("tenantId"), "default");
        String userId = safe(MDC.get("userId"), "anonymous");
        String conversationId = safe(MDC.get("conversationId"), "unknown");

        String requestModel = Optional.ofNullable(request.prompt().getOptions())
                .map(ChatOptions::getModel)
                .filter(model -> !model.isBlank())
                .orElse("unknown");

        long startNs = System.nanoTime();

        try {
            request.context().put("requestId", requestId);
            request.context().put("tenantId", tenantId);
            request.context().put("userId", userId);
            request.context().put("conversationId", conversationId);

            ChatClientResponse response = chain.nextCall(request);

            long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

            ChatResponse chatResponse = response.chatResponse();
            ChatResponseMetadata metadata = chatResponse != null ? chatResponse.getMetadata() : null;
            Usage usage = metadata != null ? metadata.getUsage() : null;

            String responseModel = metadata != null && metadata.getModel() != null
                    ? metadata.getModel()
                    : requestModel;

            Integer promptTokens = usage != null ? usage.getPromptTokens() : null;
            Integer completionTokens = usage != null ? usage.getCompletionTokens() : null;
            Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

            recordMetrics(responseModel, "success", costMs, promptTokens, completionTokens, totalTokens);

            auditSink.publish(new AiCallAuditEvent(
                    requestId,
                    tenantId,
                    userId,
                    conversationId,
                    responseModel,
                    "success",
                    costMs,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    null
            ));

            return response;
        }
        catch (RuntimeException ex) {
            long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

            recordErrorMetrics(requestModel, costMs, ex);

            auditSink.publish(new AiCallAuditEvent(
                    requestId,
                    tenantId,
                    userId,
                    conversationId,
                    requestModel,
                    "error",
                    costMs,
                    null,
                    null,
                    null,
                    ex.getClass().getSimpleName()
            ));

            throw ex;
        }
    }

    private void recordMetrics(
            String model,
            String status,
            long costMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
        Timer.builder("app.ai.chat.duration")
                .description("AI chat call duration")
                .tag("advisor", getName())
                .tag("model", safeMetricTag(model))
                .tag("status", status)
                .register(meterRegistry)
                .record(Duration.ofMillis(costMs));

        incrementTokenCounter(model, "prompt", promptTokens);
        incrementTokenCounter(model, "completion", completionTokens);
        incrementTokenCounter(model, "total", totalTokens);
    }

    private void recordErrorMetrics(String model, long costMs, RuntimeException ex) {
        Timer.builder("app.ai.chat.duration")
                .description("AuditMetricsAdvisor call duration")
                .tag("advisor", getName())
                .tag("model", safeMetricTag(model))
                .tag("status", "error")
                .register(meterRegistry)
                .record(Duration.ofMillis(costMs));

        Counter.builder("app.ai.chat.errors")
                .description("AuditMetricsAdvisor call errors")
                .tag("advisor", getName())
                .tag("model", safeMetricTag(model))
                .tag("exception", ex.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    private void incrementTokenCounter(String model, String type, Integer value) {
        if (value == null || value <= 0) {
            return;
        }

        Counter.builder("app.ai.chat.tokens")
                .description("AI chat token usage")
                .tag("advisor", getName())
                .tag("model", safeMetricTag(model))
                .tag("type", type)
                .register(meterRegistry)
                .increment(value);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeMetricTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    @Override
    public String getName() {
        return "ai-audit-metrics-advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }
}