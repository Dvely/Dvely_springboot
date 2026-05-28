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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient implements LlmPort {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final AiProperties aiProperties;

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages) {
        List<Map<String, String>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt));
        messages.forEach(m -> apiMessages.add(Map.of("role", m.role(), "content", m.content())));

        Map<String, Object> body = Map.of(
                "model",    aiProperties.getOpenai().getModel(),
                "messages", apiMessages
        );

        OpenAiResponse response = restClient()
                .post()
                .uri(API_URL)
                .body(body)
                .retrieve()
                .body(OpenAiResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI API 응답이 비어있습니다");
        }

        log.debug("OpenAI 응답 수신: model={}", aiProperties.getOpenai().getModel());
        return response.choices().get(0).message().content();
    }

    private RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + aiProperties.getOpenai().getApiKey())
                .defaultHeader("content-type",  "application/json")
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiResponse(
            @JsonProperty("choices") List<Choice> choices
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(
            @JsonProperty("message") Message message
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(
            @JsonProperty("content") String content
    ) {}
}
