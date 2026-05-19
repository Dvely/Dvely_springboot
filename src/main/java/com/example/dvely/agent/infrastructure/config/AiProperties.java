package com.example.dvely.agent.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dvely.ai")
@Getter
@Setter
public class AiProperties {

    private Anthropic anthropic = new Anthropic();
    private Openai openai = new Openai();

    @Getter
    @Setter
    public static class Anthropic {
        private String apiKey;
        private String model = "claude-opus-4-5-20251101";
    }

    @Getter
    @Setter
    public static class Openai {
        private String apiKey;
        private String model = "gpt-4o";
    }
}
