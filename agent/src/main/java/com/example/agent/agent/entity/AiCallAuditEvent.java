package com.example.agent.agent.entity;

import lombok.Data;

@Data
public class AiCallAuditEvent {
    private String requestId;
    private String tenantId;
    private String userId;
    private String conversationId;
    private String model;
    private String status;
    private Long costMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    private String errorType;

    public AiCallAuditEvent(String requestId, String tenantId, String userId, String conversationId, String responseModel, String success, long costMs, Integer promptTokens, Integer completionTokens, Integer totalTokens, Object o) {
    }
}
