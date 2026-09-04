package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

/**
 * The OpenAI chat-completions wire format, in one place, for every provider that speaks it.
 *
 * <p>OpenAI is not the only one: OpenRouter — how this deployment reaches GLM — exposes the same
 * request and response shape deliberately, so a second copy of the request building, the tool-call
 * parsing and the truncated-arguments handling would be a copy that has to be kept in step with the
 * first one forever. What actually differs per provider is the endpoint, the key, the default
 * model, a couple of headers and how the provider spells "think harder", and that is exactly what
 * {@link Endpoint} carries.</p>
 */
@Slf4j
final class OpenAiCompatibleChat {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpenAiCompatibleChat() {
    }

    /**
     * Everything about a call that depends on which provider is being called.
     *
     * @param providerName the name users see in an error message, so it says "OpenRouter(GLM)"
     *                     rather than "OpenAI" when an OpenRouter key is the one that is missing
     * @param url          the full chat-completions URL, posted to verbatim
     * @param config       the provider's configured key and model
     * @param reasoning    how this provider is asked for reasoning depth
     * @param extraHeaders headers beyond auth and content-type; empty for plain OpenAI
     * @param retry        how hard to try again when a call fails for a reason that could clear
     */
    record Endpoint(
            String providerName,
            String url,
            AiProperties.Provider config,
            LlmRequestOptions.ReasoningStyle reasoning,
            Map<String, String> extraHeaders,
            AiProperties.Retry retry
    ) {}

    /** One-shot completion: a system prompt and a transcript in, the assistant's text out. */
    static String complete(Endpoint endpoint,
                           String systemPrompt,
                           List<LlmMessage> messages,
                           AiModelOptions modelOptions) {
        LlmProviderErrors.requireApiKey(endpoint.providerName(), endpoint.config().getApiKey());

        List<Map<String, String>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt));
        messages.forEach(m -> apiMessages.add(Map.of("role", m.role(), "content", m.content())));

        Map<String, Object> body = baseBody(endpoint, apiMessages, modelOptions);

        String raw = LlmProviderErrors.translate(endpoint.providerName(), endpoint.retry(), () -> restClient(endpoint)
                .post()
                .uri(endpoint.url())
                .body(body)
                .retrieve()
                .body(String.class));

        String content = firstMessageContent(endpoint, raw);
        log.debug("{} 응답 수신: model={}", endpoint.providerName(), body.get("model"));
        return content;
    }

    /** Tool-calling completion: the calls the model wants run, plus its raw assistant message. */
    static LlmToolResponse completeWithTools(Endpoint endpoint,
                                             String systemPrompt,
                                             List<Map<String, Object>> messages,
                                             List<ToolDefinition> tools,
                                             AiModelOptions modelOptions) {
        LlmProviderErrors.requireApiKey(endpoint.providerName(), endpoint.config().getApiKey());

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

        Map<String, Object> body = baseBody(endpoint, apiMessages, modelOptions);
        body.put("tools", toolsPayload);

        String raw = LlmProviderErrors.translate(endpoint.providerName(), endpoint.retry(), () -> restClient(endpoint)
                .post()
                .uri(endpoint.url())
                .body(body)
                .retrieve()
                .body(String.class));

        log.debug("{} Tool API 응답 수신", endpoint.providerName());
        return parseToolResponse(endpoint, raw);
    }

    private static Map<String, Object> baseBody(Endpoint endpoint,
                                                List<?> apiMessages,
                                                AiModelOptions modelOptions) {
        Map<String, Object> body = new HashMap<>();
        body.put("model",    modelOptions.modelOr(endpoint.config().getModel()));
        body.put("messages", apiMessages);
        LlmRequestOptions.applyOpenAiCompatible(body, modelOptions, endpoint.reasoning());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static String firstMessageContent(Endpoint endpoint, String raw) {
        try {
            Map<String, Object> response = OBJECT_MAPPER.readValue(raw, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException(endpoint.providerName() + " API 응답이 비어있습니다");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null) {
                throw new IllegalStateException(endpoint.providerName() + " API 응답이 비어있습니다");
            }
            return (String) content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 응답 파싱 실패: {}", endpoint.providerName(), raw, e);
            throw new IllegalStateException(endpoint.providerName() + " API 응답 파싱 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static LlmToolResponse parseToolResponse(Endpoint endpoint, String raw) {
        try {
            Map<String, Object> response = OBJECT_MAPPER.readValue(raw, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message      = (Map<String, Object>) choices.get(0).get("message");
            String finishReason              = (String) choices.get(0).get("finish_reason");

            List<Map<String, Object>> rawToolCalls =
                    (List<Map<String, Object>>) message.get("tool_calls");

            List<ToolCall> toolCalls = new ArrayList<>();
            if (rawToolCalls != null) {
                for (Map<String, Object> tc : rawToolCalls) {
                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    toolCalls.add(new ToolCall(
                            (String) tc.get("id"),
                            (String) function.get("name"),
                            parseArguments(endpoint, (String) function.get("arguments"))
                    ));
                }
            }

            // contentBlocks = [assistantMessage] — 호출 측 루프에서 그대로 messages에 추가
            return new LlmToolResponse(toolCalls, List.of(message), finishReason);

        } catch (Exception e) {
            log.error("{} Tool 응답 파싱 실패: {}", endpoint.providerName(), raw, e);
            throw new RuntimeException(endpoint.providerName() + " Tool API 응답 파싱 실패", e);
        }
    }

    /**
     * A tool call's {@code arguments} is a JSON string the model produced, so it can be incomplete
     * — most often when generation stopped at the output limit mid-arguments
     * ({@code finish_reason=length}). That used to abort the whole parse and fail the task with
     * "OpenAI Tool API 응답 파싱 실패", losing every round of work already done in the container.
     * Degrading to empty arguments keeps the response usable: CodeAgentService answers the call
     * with a "missing argument" tool result and the model retries it, and for the truncation case
     * it skips the call outright on {@code finish_reason}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(Endpoint endpoint, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(arguments, Map.class);
        } catch (Exception e) {
            log.warn("{} tool 인자 JSON 파싱 실패, 빈 인자로 처리: {}", endpoint.providerName(), e.getMessage());
            return Map.of();
        }
    }

    private static RestClient restClient(Endpoint endpoint) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(LlmHttp.timeoutFactory())
                .defaultHeader("Authorization", "Bearer " + endpoint.config().getApiKey())
                .defaultHeader("content-type",  "application/json");
        endpoint.extraHeaders().forEach(builder::defaultHeader);
        return builder.build();
    }
}
