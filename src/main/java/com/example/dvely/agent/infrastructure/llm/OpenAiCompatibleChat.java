package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.LlmUsage;
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

    /**
     * 한 번의 완성 호출 결과.
     *
     * <p>본문만 돌려주던 예전 모양으로는 응답의 {@code usage} 가 파싱되자마자 버려졌다 — 그래서
     * 토큰을 얼마나 쓰는지 아무도 몰랐다. 기록은 제공자 빈이 하고(어느 {@link AiProvider} 로
     * 집계할지는 이 정적 클래스가 알 수 없다), 이 record 가 그 사이를 잇는다.</p>
     *
     * @param model 제공자가 실제로 답한 모델명. 요청이 슬러그를 생략했거나 게이트웨이가 다른
     *              모델로 라우팅했을 수 있으므로 요청값이 아니라 응답값을 남긴다
     */
    record Completion(String content, String model, LlmUsage usage) {}

    /** One-shot completion: a system prompt and a transcript in, the assistant's text out. */
    static Completion complete(Endpoint endpoint,
                               String systemPrompt,
                               List<LlmMessage> messages,
                               AiModelOptions modelOptions) {
        LlmProviderErrors.requireApiKey(endpoint.providerName(), endpoint.config().getApiKey());

        List<Map<String, String>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt));
        messages.forEach(m -> apiMessages.add(Map.of("role", m.role(), "content", m.content())));

        Map<String, Object> body = baseBody(endpoint, apiMessages, modelOptions);

        String raw = LlmProviderErrors.translate(endpoint.providerName(), endpoint.retry(), () -> post(endpoint)
                .body(body)
                .retrieve()
                .body(String.class));

        String content = firstMessageContent(endpoint, raw);
        log.debug("{} 응답 수신: model={}", endpoint.providerName(), body.get("model"));
        return new Completion(
                content,
                answeredModel(endpoint, raw, String.valueOf(body.get("model"))),
                readUsage(endpoint, raw));
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

        String raw = LlmProviderErrors.translate(endpoint.providerName(), endpoint.retry(), () -> post(endpoint)
                .body(body)
                .retrieve()
                .body(String.class));

        log.debug("{} Tool API 응답 수신", endpoint.providerName());
        return parseToolResponse(endpoint, raw, readUsage(endpoint, raw));
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
            log.error("{} 응답 파싱 실패: {}", endpoint.providerName(), LlmLogPreview.of(raw), e);
            throw new IllegalStateException(endpoint.providerName() + " API 응답 파싱 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static LlmToolResponse parseToolResponse(Endpoint endpoint, String raw, LlmUsage usage) {
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
            return new LlmToolResponse(toolCalls, List.of(message), finishReason, usage);

        } catch (Exception e) {
            log.error("{} Tool 응답 파싱 실패: {}", endpoint.providerName(), LlmLogPreview.of(raw), e);
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

    /**
     * 이 호출 하나짜리 요청.
     *
     * <p>클라이언트는 공용 하나를 계속 쓰고, 제공자마다 달라지는 것(키, 부가 헤더)만 요청에 싣는다.
     * 키를 클라이언트의 기본 헤더로 박으면 그 인스턴스에 고정되는데, 여기는 제공자가 이미 여럿인
     * 경로다 — 한 번 고정된 키가 다른 제공자·다른 사용자의 요청에 실려 나가는 사고는 조용히
     * 일어나고 로그에도 남지 않는다.</p>
     */
    private static RestClient.RequestBodySpec post(Endpoint endpoint) {
        RestClient.RequestBodySpec request = LlmHttp.client()
                .post()
                .uri(endpoint.url())
                .header("Authorization", "Bearer " + endpoint.config().getApiKey());
        endpoint.extraHeaders().forEach(request::header);
        return request;
    }

    /**
     * 응답의 {@code usage}.
     *
     * <p>파싱이 어긋나도 던지지 않는다. 이 시점의 호출은 이미 성공했고, 계측 하나를 잃는 것이
     * 성공한 호출을 실패로 만드는 것보다 낫다.</p>
     */
    @SuppressWarnings("unchecked")
    private static LlmUsage readUsage(Endpoint endpoint, String raw) {
        try {
            return LlmUsageParser.openAiCompatible(OBJECT_MAPPER.readValue(raw, Map.class));
        } catch (Exception exception) {
            log.debug("{} 사용량 파싱 실패 — 계측만 건너뜁니다: {}",
                    endpoint.providerName(), exception.getClass().getSimpleName());
            return LlmUsage.NONE;
        }
    }

    @SuppressWarnings("unchecked")
    private static String answeredModel(Endpoint endpoint, String raw, String requestedModel) {
        try {
            Map<String, Object> response = OBJECT_MAPPER.readValue(raw, Map.class);
            return response.get("model") instanceof String named && !named.isBlank()
                    ? named
                    : requestedModel;
        } catch (Exception exception) {
            return requestedModel;
        }
    }
}
