package com.example.dvely.agent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentPersistenceConfig {

    @Bean
    public ObjectMapper agentPlanObjectMapper() {
        return new ObjectMapper();
    }
}
