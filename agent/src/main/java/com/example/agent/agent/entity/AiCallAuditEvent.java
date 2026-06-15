package com.example.agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
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

}
