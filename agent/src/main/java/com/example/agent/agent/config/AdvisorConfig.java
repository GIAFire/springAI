package com.example.agent.agent.config;

import com.example.agent.agent.rag.advisors.TraceMetricsAdvisor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
@Configuration
public class AdvisorConfig {

    @Bean
    public TraceMetricsAdvisor traceMetricsAdvisor(MeterRegistry meterRegistry,
                                                     ObjectProvider<Tracer> tracerProvider) {
        return new TraceMetricsAdvisor(
                meterRegistry,
                tracerProvider.getIfAvailable());
    }
}
