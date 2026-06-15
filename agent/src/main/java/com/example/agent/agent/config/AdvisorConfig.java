package com.example.agent.agent.config;

import com.example.agent.agent.rag.advisors.AuditAdvisor;
import com.example.agent.agent.rag.advisors.TraceMetricsAdvisor;
import com.example.agent.agent.service.CallAuditSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
@Configuration
public class AdvisorConfig {

    @Autowired
    @Qualifier("loggingAiCallAuditSink")
    private CallAuditSink callAuditSink;

    @Bean
    public TraceMetricsAdvisor traceMetricsAdvisor(MeterRegistry meterRegistry,
                                                     ObjectProvider<Tracer> tracerProvider) {
        return new TraceMetricsAdvisor(
                meterRegistry,
                tracerProvider.getIfAvailable());
    }

    @Bean
    public AuditAdvisor auditMetricsAdvisor(){
        return new AuditAdvisor(callAuditSink);
    }
}
