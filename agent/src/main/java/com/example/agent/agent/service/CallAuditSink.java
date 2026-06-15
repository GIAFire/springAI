package com.example.agent.agent.service;

import com.example.agent.agent.entity.AiCallAuditEvent;

public interface CallAuditSink {

    void publish(AiCallAuditEvent event);

}
