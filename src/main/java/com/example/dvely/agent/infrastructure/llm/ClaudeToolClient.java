package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.domain.value.AiModelOptions;
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
public class ClaudeToolClient implements LlmToolPort {

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    // Output budget per round. 4096 was not enough to emit one real source file in a single
    // write_file call, so generation stopped mid-arguments (stop_reason=max_tokens) on ordinary
    // components — CodeAgentService refuses to run a call cut off that way, which costs the round.
    // Sized to fit a typical component write with headroom; it is a ceiling, not an allocation, so
    // rounds that emit less are unaffected.
    private static final int    MAX_TOKENS  = 8192;

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools) {
        return completeWithTools(systemPrompt, messages, tools, AiModelOptions.defaults());
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools,
            AiModelOptions modelOptions) {

        List<Map<String, Object>> toolsPayload = tools.stream()
                .map(t -> Map.of(
                        "name",         t.name(),
                        "description",  t.description(),
                        "input_schema", t.inputSchema()
                ))
                .toList();

        LlmProviderErrors.requireApiKey(ClaudeClient.PROVIDER_NAME, aiProperties.getAnthropic().getApiKey());

        Map<String, Object> body = new HashMap<>();
        body.put("model",    modelOptions.modelOr(aiProperties.getAnthropic().getModel()));
        body.put("system",   systemPrompt);
        body.put("tools",    toolsPayload);
        body.put("messages", messages);
        LlmRequestOptions.applyAnthropic(body, modelOptions, MAX_TOKENS);

        String raw = LlmProviderErrors.translate(ClaudeClient.PROVIDER_NAME, aiProperties.getRetry(), () -> restClient()
                .post()
                .uri(API_URL)
                .body(body)
                .retrieve()
                .body(String.class));

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
                .requestFactory(LlmHttp.timeoutFactory())
                .defaultHeader("x-api-key",        aiProperties.getAnthropic().getApiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader("content-type",      "application/json")
                .build();
    }
}
