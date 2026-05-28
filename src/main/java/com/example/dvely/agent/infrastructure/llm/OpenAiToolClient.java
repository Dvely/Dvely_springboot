package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiToolClient {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools) {

        List<Map<String, Object>> toolsPayload = tools.stream()
                .map(t -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name",        t.name(),
                                "description", t.description(),
                                "parameters",  t.inputSchema()
                        )
                ))
                .toList();

        List<Map<String, Object>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt));
        apiMessages.addAll(messages);

        Map<String, Object> body = new HashMap<>();
        body.put("model",    aiProperties.getOpenai().getModel());
        body.put("messages", apiMessages);
        body.put("tools",    toolsPayload);

        String raw = restClient()
                .post()
                .uri(API_URL)
                .body(body)
                .retrieve()
                .body(String.class);

        log.debug("OpenAI Tool API 응답 수신");
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private LlmToolResponse parse(String raw) {
        try {
            Map<String, Object> response = objectMapper.readValue(raw, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message      = (Map<String, Object>) choices.get(0).get("message");
            String finishReason              = (String) choices.get(0).get("finish_reason");

            List<Map<String, Object>> rawToolCalls =
                    (List<Map<String, Object>>) message.get("tool_calls");

            List<ToolCall> toolCalls = new ArrayList<>();
            if (rawToolCalls != null) {
                for (Map<String, Object> tc : rawToolCalls) {
                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    Map<String, Object> input    = objectMapper.readValue(
                            (String) function.get("arguments"), Map.class);
                    toolCalls.add(new ToolCall(
                            (String) tc.get("id"),
                            (String) function.get("name"),
                            input
                    ));
                }
            }

            // contentBlocks = [assistantMessage] — OpenAI 루프에서 그대로 messages에 추가
            return new LlmToolResponse(toolCalls, List.of(message), finishReason);

        } catch (Exception e) {
            log.error("OpenAI Tool 응답 파싱 실패: {}", raw, e);
            throw new RuntimeException("OpenAI Tool API 응답 파싱 실패", e);
        }
    }

    private RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + aiProperties.getOpenai().getApiKey())
                .defaultHeader("content-type",  "application/json")
                .build();
    }
}
