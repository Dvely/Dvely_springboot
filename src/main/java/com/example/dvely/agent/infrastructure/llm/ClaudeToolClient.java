package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
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
public class ClaudeToolClient {

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int    MAX_TOKENS  = 4096;

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools) {

        List<Map<String, Object>> toolsPayload = tools.stream()
                .map(t -> Map.of(
                        "name",         t.name(),
                        "description",  t.description(),
                        "input_schema", t.inputSchema()
                ))
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model",      aiProperties.getAnthropic().getModel());
        body.put("max_tokens", MAX_TOKENS);
        body.put("system",     systemPrompt);
        body.put("tools",      toolsPayload);
        body.put("messages",   messages);

        String raw = restClient()
                .post()
                .uri(API_URL)
                .body(body)
                .retrieve()
                .body(String.class);

        log.debug("Claude Tool API 응답 수신");
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private LlmToolResponse parse(String raw) {
        try {
            Map<String, Object> response   = objectMapper.readValue(raw, Map.class);
            String              stopReason = (String) response.getOrDefault("stop_reason", "end_turn");
            List<Map<String, Object>> contentBlocks =
                    (List<Map<String, Object>>) response.getOrDefault("content", List.of());

            List<ToolCall> toolCalls = new ArrayList<>();
            for (Map<String, Object> block : contentBlocks) {
                if ("tool_use".equals(block.get("type"))) {
                    toolCalls.add(new ToolCall(
                            (String) block.get("id"),
                            (String) block.get("name"),
                            (Map<String, Object>) block.get("input")
                    ));
                }
            }

            return new LlmToolResponse(toolCalls, contentBlocks, stopReason);
        } catch (Exception e) {
            log.error("Claude Tool 응답 파싱 실패: {}", raw, e);
            throw new RuntimeException("Claude Tool API 응답 파싱 실패", e);
        }
    }

    private RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("x-api-key",        aiProperties.getAnthropic().getApiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader("content-type",      "application/json")
                .build();
    }
}
