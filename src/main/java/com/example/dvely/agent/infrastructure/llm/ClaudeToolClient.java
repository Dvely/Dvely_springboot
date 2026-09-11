package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.LlmUsage;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeToolClient implements LlmToolPort {

    private static final String API_VERSION = "2023-06-01";
    // Output budget per round. 4096 was not enough to emit one real source file in a single
    // write_file call, so generation stopped mid-arguments (stop_reason=max_tokens) on ordinary
    // components — CodeAgentService refuses to run a call cut off that way, which costs the round.
    // Sized to fit a typical component write with headroom; it is a ceiling, not an allocation, so
    // rounds that emit less are unaffected.
    private static final int    MAX_TOKENS  = 8192;

    private final AiProperties aiProperties;
    private final LlmUsageRecorder llmUsageRecorder;
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
                .map(t -> Map.<String, Object>of(
                        "name",         t.name(),
                        "description",  t.description(),
                        "input_schema", t.inputSchema()
                ))
                .toList();

        LlmProviderErrors.requireApiKey(ClaudeClient.PROVIDER_NAME, aiProperties.getAnthropic().getApiKey());

        String model = modelOptions.modelOr(aiProperties.getAnthropic().getModel());
        Map<String, Object> body = new HashMap<>();
        body.put("model",    model);
        // 캐시 브레이크포인트는 tools → system → messages 순으로 렌더된다. 이 루프의 접두는
        // 고정이다 — 시스템 프롬프트와 도구 정의가 상수이고 트랜스크립트는 덧붙이기만 한다 —
        // 그래서 마커가 실제로 값을 한다. 자세한 배치 근거는 AnthropicPromptCache 참고.
        body.put("system",   AnthropicPromptCache.systemBlocks(systemPrompt));
        body.put("tools",    AnthropicPromptCache.toolsWithCacheControl(toolsPayload));
        body.put("messages", AnthropicPromptCache.messagesWithRollingCacheControl(messages));
        LlmRequestOptions.applyAnthropic(body, modelOptions, MAX_TOKENS);

        String raw = LlmProviderErrors.translate(ClaudeClient.PROVIDER_NAME, aiProperties.getRetry(), () -> LlmHttp.client()
                .post()
                .uri(aiProperties.getAnthropic().getBaseUrl())
                // 키는 호출마다 싣는다 — 공용 클라이언트의 기본 헤더로 박으면 인스턴스에 고정된다.
                .header("x-api-key", aiProperties.getAnthropic().getApiKey())
                .header("anthropic-version", API_VERSION)
                .body(body)
                .retrieve()
                .body(String.class));

        log.debug("Claude Tool API 응답 수신");
        ParsedResponse response = parse(raw, model);
        // 기록은 parse 밖이다. 안에서 하면 예산 초과 예외가 parse 의 catch(Exception) 에 걸려
        // "Tool API 응답 파싱 실패" 로 둔갑한다 — 사용자는 무엇에 걸렸는지 못 보게 된다.
        llmUsageRecorder.record(AiProvider.ANTHROPIC, response.model(), response.usage());
        return response.response();
    }

    /** 응답과, 그것을 실제로 답한 모델명을 함께 들고 나온다(기록은 호출부에서 한다). */
    private record ParsedResponse(LlmToolResponse response, String model) {
        LlmUsage usage() {
            return response.usage();
        }
    }

    @SuppressWarnings("unchecked")
    private ParsedResponse parse(String raw, String requestedModel) {
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

            LlmUsage usage = LlmUsageParser.anthropic(response);
            Object answeredModel = response.get("model");
            return new ParsedResponse(
                    new LlmToolResponse(toolCalls, contentBlocks, stopReason, usage),
                    answeredModel instanceof String named ? named : requestedModel);
        } catch (Exception e) {
            log.error("Claude Tool 응답 파싱 실패: {}", LlmLogPreview.of(raw), e);
            throw new RuntimeException("Claude Tool API 응답 파싱 실패", e);
        }
    }
}
