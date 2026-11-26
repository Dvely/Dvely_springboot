package com.example.dvely.chat.application.pin;

import com.example.dvely.chat.domain.model.ChatMessagePin;
import com.example.dvely.chat.domain.model.ChatMessagePinLabel;
import com.example.dvely.chat.domain.repository.ChatMessagePinRepository;
import com.example.dvely.deviceshare.application.session.PinResolution;
import com.example.dvely.deviceshare.application.session.PinResultSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 서버 프리뷰에서 짚어 메시지에 붙인 곳(설계 §16.6): 받을 때 정리하고, 에이전트에게 줄 때 글로 만든다.
 *
 * <p>정리는 기기 공유의 짚기 결과와 같은 검사기({@link PinResultSanitizer})로 한다. 결과는 프리뷰 문서 안의 브리지가 만든 것이고,
 * 프리뷰 코드는 그것을 꾸밀 수 있다 — 그래서 아는 필드만, 상한 안에서만 남긴다(§16.2).</p>
 */
@Service
public class PreviewPinService {

    /** ①~⑤. 기기 공유의 여러 곳(§15.5)과 같다 */
    public static final int MAX_PINS = 5;

    /** 정리한 기술 하나의 상한. 기기 결과(§15.9)와 같다 */
    static final int MAX_PIN_JSON_BYTES = 48 * 1024;

    /** 칩 이름 칸(VARCHAR 255)에 맞춘다. 실제 이름은 80자 안쪽이다 */
    private static final int MAX_LABEL = 255;

    static final String EMPTY_SPOT_LABEL = "빈 곳";

    private final ChatMessagePinRepository repository;
    private final ObjectMapper json = new ObjectMapper();
    private final PinResultSanitizer sanitizer = new PinResultSanitizer(json);

    public PreviewPinService(ChatMessagePinRepository repository) {
        this.repository = repository;
    }

    /**
     * 요청의 짚은 곳을 저장할 모양으로 만든다. 하나라도 틀리면 통째로 거절한다 — 사용자가 고른 곳 중 일부만 조용히
     * 빠진 채 에이전트에게 가면, 말과 짚은 곳의 번호가 어긋난다.
     *
     * @throws IllegalArgumentException 6개 이상이거나, 결과가 짚기 모양이 아니거나, 너무 클 때(400)
     */
    public List<ChatMessagePin> normalize(List<PreviewPinInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > MAX_PINS) {
            throw new IllegalArgumentException("프리뷰에서 짚은 곳은 " + MAX_PINS + "개까지 보낼 수 있습니다.");
        }
        List<ChatMessagePin> pins = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            int position = i + 1;
            PreviewPinInput input = inputs.get(i);
            JsonNode raw = input == null || input.result() == null ? null : json.valueToTree(input.result());
            PinResolution resolution = sanitizer.sanitize(raw)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "짚은 곳 " + position + "의 형식이 올바르지 않습니다. 다시 짚어 주세요."));
            ObjectNode description = resolution.description();
            // 브리지가 읽는 location 은 게이트웨이 경로다 — 그 안에 프리뷰 열람 토큰이 있다. 저장·프롬프트(외부 LLM)로 가기 전에
            // 앱이 보는 경로로 바꾼다(2026-11-26 로컬 실측에서 "화면: 프리뷰 · /api/v1/previews/{sid}/{token}/" 로 나갔다)
            if (description.path("screen") instanceof ObjectNode screen) {
                for (String field : List.of("label", "url")) {
                    if (screen.path(field).isTextual()) {
                        screen.put(field, appPath(screen.path(field).asText()));
                    }
                }
            }
            // 브리지는 소스 후보와 컴포넌트 파일 위치를 내지 않는다(§16.4 — 그 경로는 빌드 컨테이너의 것). 있다면 꾸민 것이다
            description.remove("sourceHints");
            for (JsonNode component : description.path("components")) {
                if (component instanceof ObjectNode named) {
                    named.remove("file");
                    named.remove("line");
                }
            }
            JsonNode scopes = description.path("scopes");
            if (input.scope() != null && scopes.isArray() && input.scope() >= 0 && input.scope() < scopes.size()) {
                description.put("scope", input.scope().intValue());
            }
            // §15.3: 같은 것 모두는 고른 칸에 같은 것이 있을 때만
            if (Boolean.TRUE.equals(input.peers())
                    && PinResultSanitizer.selectedScope(description).map(s -> s.path("peers").isObject()).orElse(false)) {
                description.put("peers", true);
            }
            String text = write(description);
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_PIN_JSON_BYTES) {
                throw new IllegalArgumentException("짚은 곳 " + position + "이 너무 큽니다. 범위를 좁혀 다시 짚어 주세요.");
            }
            pins.add(new ChatMessagePin(position, label(description), text));
        }
        return List.copyOf(pins);
    }

    public void attach(Long messageId, List<ChatMessagePin> pins) {
        repository.saveAll(messageId, pins);
    }

    public Map<Long, List<ChatMessagePinLabel>> labelsOf(Collection<Long> messageIds) {
        return repository.findLabelsByMessageIds(messageIds);
    }

    /** 이 메시지에 붙은 곳을 에이전트용 글로. 없으면 빈 값 */
    public Optional<String> promptFor(Long messageId) {
        return render(repository.findByMessageId(messageId));
    }

    /** 이 태스크를 낳은 사용자 발화에 붙은 곳을 에이전트용 글로. 없으면 빈 값 */
    public Optional<String> promptForTask(String taskId) {
        return render(repository.findByTaskId(taskId));
    }

    private Optional<String> render(List<ChatMessagePin> pins) {
        if (pins.isEmpty()) {
            return Optional.empty();
        }
        List<JsonNode> places = new ArrayList<>();
        for (ChatMessagePin pin : pins) {
            try {
                places.add(json.readTree(pin.elementJson()));
            } catch (JsonProcessingException e) {
                // 저장할 때 우리가 쓴 JSON 이다. 읽지 못하면 그 곳만 빼고 나머지를 준다
            }
        }
        return places.isEmpty() ? Optional.empty() : Optional.of(PreviewPinPromptRenderer.render(places));
    }

    /** 게이트웨이 접두({@code [scheme://host]/api/v1/previews/{sid}/{token}}) — 세션 id 와 열람 토큰을 담는다 */
    private static final java.util.regex.Pattern GATEWAY_PREFIX = java.util.regex.Pattern.compile(
            "^(?:[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*)?/api/v1/previews/[^/?#]+/[^/?#]+");

    /** 게이트웨이 경로면 앱이 보는 경로로({@code /settings?tab=1}), 아니면 그대로 */
    static String appPath(String value) {
        java.util.regex.Matcher m = GATEWAY_PREFIX.matcher(value);
        if (!m.lookingAt()) {
            return value;
        }
        String rest = value.substring(m.end());
        if (rest.isEmpty()) {
            return "/";
        }
        return rest.startsWith("/") ? rest : "/" + rest;
    }

    private String label(JsonNode description) {
        String label = PinResultSanitizer.placeLabel(description);
        if (label == null || label.isBlank()) {
            label = EMPTY_SPOT_LABEL;
        }
        return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label;
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("짚은 곳을 저장할 모양으로 만들지 못했습니다", e);
        }
    }
}
