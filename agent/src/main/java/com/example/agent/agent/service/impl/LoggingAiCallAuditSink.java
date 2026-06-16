package com.example.agent.agent.service.impl;

import com.example.agent.agent.entity.AiCallAuditEvent;
import com.example.agent.agent.service.CallAuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingAiCallAuditSink implements CallAuditSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingAiCallAuditSink.class);

    @Override
    public void publish(AiCallAuditEvent event) {
        log.info(
                "AuditAdvisor. requestId={}, tenantId={}, userId={}, conversationId={}, model={}, status={}, costMs={}, promptTokens={}, completionTokens={}, totalTokens={}, errorType={}",
                event.getRequestId(),
                event.getTenantId(),
                event.getUserId(),
                event.getConversationId(),
                event.getModel(),
                event.getStatus(),
                event.getCostMs(),
                event.getPromptTokens(),
                event.getCompletionTokens(),
                event.getTotalTokens(),
                event.getErrorType()
        );
    }
}