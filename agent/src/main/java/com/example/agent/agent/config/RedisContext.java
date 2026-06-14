package com.example.agent.agent.config;

import org.springframework.ai.chat.client.advisor.api.Advisor;

public class RedisContext implements Advisor {
    @Override
    public String getName() {
        return null;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
