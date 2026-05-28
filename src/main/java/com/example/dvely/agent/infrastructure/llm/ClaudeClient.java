package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeClient implements LlmPort {

    private static final String API_URL      = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION  = "2023-06-01";
    private static final int    MAX_TOKENS   = 1024;

    private final AiProperties aiProperties;

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages) {
        List<Map<String, String>> apiMessages = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = Map.of(
                "model",      aiProperties.getAnthropic().getModel(),
                "max_tokens", MAX_TOKENS,
                "system",     systemPrompt,
                "messages",   apiMessages
        );

        ClaudeResponse response = restClient()
                .post()
                .uri(API_URL)
                .body(body)
                .retrieve()
                .body(ClaudeResponse.class);

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new IllegalStateException("Claude API 응답이 비어있습니다");
        }

        log.debug("Claude 응답 수신: model={}", aiProperties.getAnthropic().getModel());
        return response.content().get(0).text();
    }

    private RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("x-api-key",         aiProperties.getAnthropic().getApiKey())
                .defaultHeader("anthropic-version",  API_VERSION)
                .defaultHeader("content-type",       "application/json")
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeResponse(
            @JsonProperty("content") List<ContentBlock> content
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentBlock(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text
    ) {}
}
