package com.example.agent.agent.rag.advisors;

import com.example.agent.agent.entity.AiCallAuditEvent;
import com.example.agent.agent.service.CallAuditSink;
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
import java.util.concurrent.atomic.AtomicReference;

public class AuditAdvisor implements CallAdvisor, StreamAdvisor {

    private final CallAuditSink auditSink;

    public AuditAdvisor(CallAuditSink auditSink) {
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

            publishAuditEvent(requestId, tenantId, userId, conversationId, responseModel,
                    "success", costMs, promptTokens, completionTokens, totalTokens, null);

            return response;
        }
        catch (RuntimeException ex) {
            long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

            publishAuditEvent(requestId, tenantId, userId, conversationId, requestModel,
                    "error", costMs, null, null, null, ex.getClass().getSimpleName());

            throw ex;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request,
            StreamAdvisorChain chain
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
        AtomicReference<StreamUsageSnapshot> usageSnapshot = new AtomicReference<>(
                new StreamUsageSnapshot(requestModel, null, null, null));

        request.context().put("requestId", requestId);
        request.context().put("tenantId", tenantId);
        request.context().put("userId", userId);
        request.context().put("conversationId", conversationId);

        return chain.nextStream(request)
                .doOnNext(response -> updateStreamUsageSnapshot(response, requestModel, usageSnapshot))
                .doOnComplete(() -> {
                    long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
                    StreamUsageSnapshot snapshot = usageSnapshot.get();

                    publishAuditEvent(requestId, tenantId, userId, conversationId, snapshot.model(),
                            "success", costMs, snapshot.promptTokens(), snapshot.completionTokens(),
                            snapshot.totalTokens(), null);
                })
                .doOnError(error -> {
                    long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

                    publishAuditEvent(requestId, tenantId, userId, conversationId, requestModel,
                            "error", costMs, null, null, null, error.getClass().getSimpleName());
                });
    }

    private void updateStreamUsageSnapshot(
            ChatClientResponse response,
            String requestModel,
            AtomicReference<StreamUsageSnapshot> usageSnapshot
    ) {
        ChatResponse chatResponse = response.chatResponse();
        ChatResponseMetadata metadata = chatResponse != null ? chatResponse.getMetadata() : null;
        Usage usage = metadata != null ? metadata.getUsage() : null;

        if (usage == null) {
            return;
        }

        String responseModel = metadata.getModel() != null
                ? metadata.getModel()
                : requestModel;

        usageSnapshot.set(new StreamUsageSnapshot(
                responseModel,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()
        ));
    }

    private void publishAuditEvent(
            String requestId,
            String tenantId,
            String userId,
            String conversationId,
            String model,
            String status,
            long costMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String errorType
    ) {
        auditSink.publish(new AiCallAuditEvent(
                requestId,
                tenantId,
                userId,
                conversationId,
                model,
                status,
                costMs,
                promptTokens,
                completionTokens,
                totalTokens,
                errorType
        ));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String getName() {
        return "ai-call-audit-advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    private record StreamUsageSnapshot(
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
    }
}
