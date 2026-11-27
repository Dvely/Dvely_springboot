package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;

/**
 * Claude Code 의 {@code --output-format stream-json} 을 한 줄씩 읽는다(#600). 실행 하나에 하나.
 *
 * <p>읽는 것은 둘이다. 에이전트가 쓴 도구({@code assistant} 줄의 {@code tool_use})와 중간 말({@code text})은
 * {@link CodingAgentActivity} 로 넘기고, 마지막 {@code result} 줄의 {@code result} 는 이 실행의 답으로 둔다 —
 * 예전에 텍스트 출력이 주던 바로 그 글이다.</p>
 *
 * <p>말은 한 박자 늦게 넘긴다. 다음 도구 호출이 오면 그 앞의 말은 중간 말("버튼 규칙을 찾아 보겠습니다")이고,
 * {@code result} 가 오면 그 말은 맺음말이다 — 맺음말은 요약으로 따로 보이므로 진행 줄로 두 번 보이지 않게 버린다
 * (실측: 맺음말이 160자에서 잘린 채 요약 바로 위에 한 번 더 나왔다).</p>
 *
 * <p>모르는 줄·JSON 이 아닌 줄은 그냥 넘긴다. CLI 판이 바뀌어 줄 모양이 달라져도 진행 표시가 비는 것으로
 * 그치고 실행은 그대로 간다. 운영자가 {@code argv-prefix} 에서 stream-json 을 빼면 {@code result} 줄이 없으니
 * 답은 stdout 그대로다.</p>
 *
 * <p>모양은 고정한 판(docker/coding-agent/Dockerfile 의 2.1.316)에서 실제로 찍어 확인했다.
 * {@code --bare} 에서 쓸 수 있는 도구는 Bash·Edit·Read 셋이지만, 다른 도구 이름도 읽어 둔다.</p>
 */
final class ClaudeStreamReader implements AbstractCliCodingAgentAdapter.StreamReader {

    private final ObjectMapper json = new ObjectMapper();
    private final ActivityEmitter emitter;
    private volatile String answer;
    /** 아직 중간 말인지 맺음말인지 모르는 마지막 말 */
    private String pendingText;

    /**
     * @param apiKey the run's own key, masked out of every target (see {@link ActivityEmitter})
     */
    ClaudeStreamReader(Consumer<CodingAgentActivity> onActivity, String apiKey) {
        this.emitter = new ActivityEmitter(onActivity, apiKey);
    }

    @Override
    public void accept(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        // 도구 결과(user 줄)는 파일 내용을 통째로 싣는다. 쓰지 않으니 파싱하지 않는다
        if (!trimmed.startsWith("{") || trimmed.startsWith("{\"type\":\"user\"")) {
            return;
        }
        JsonNode event;
        try {
            event = json.readTree(trimmed);
        } catch (Exception e) {
            return;
        }
        switch (event.path("type").asText("")) {
            case "assistant" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    activityOf(block);
                }
            }
            case "result" -> {
                pendingText = null;
                answer = resultText(event);
            }
            default -> {
                // system·user·스트림 조각 등은 보이지 않는다
            }
        }
    }

    /**
     * @param stdout the outcome's stdout, used when the stream never produced a result line
     * @return the agent's closing text
     */
    @Override
    public String answer(String stdout) {
        return answer != null ? answer : stdout;
    }

    private void activityOf(JsonNode block) {
        String type = block.path("type").asText("");
        if ("text".equals(type)) {
            flushText();
            pendingText = ActivityEmitter.plain(block.path("text").asText(""));
            return;
        }
        if (!"tool_use".equals(type)) {
            return;
        }
        flushText();
        JsonNode input = block.path("input");
        switch (block.path("name").asText("")) {
            case "Read" -> emitter.emit(Kind.READ, ActivityEmitter.path(input.path("file_path").asText("")));
            case "Edit", "MultiEdit", "Write" -> emitter.emit(Kind.EDIT, ActivityEmitter.path(input.path("file_path").asText("")));
            case "NotebookEdit" -> emitter.emit(Kind.EDIT, ActivityEmitter.path(input.path("notebook_path").asText("")));
            case "Bash" -> emitter.emit(Kind.COMMAND, ActivityEmitter.firstLine(input.path("command").asText("")));
            case "Grep", "Glob" -> emitter.emit(Kind.SEARCH, ActivityEmitter.oneLine(input.path("pattern").asText("")));
            default -> {
                // 할 일 목록·하위 에이전트 등은 화면에 보일 대상이 없다
            }
        }
    }

    private void flushText() {
        String text = pendingText;
        pendingText = null;
        if (text != null) {
            emitter.emit(Kind.MESSAGE, text);
        }
    }

    /** {@code success} 든 오류든 {@code result} 글이 답이다. 글이 없으면 무엇으로 끝났는지만 */
    private static String resultText(JsonNode event) {
        String text = event.path("result").asText("");
        if (!text.isBlank()) {
            return text;
        }
        String subtype = event.path("subtype").asText("");
        return "Claude Code 가 남긴 말 없이 끝났습니다" + (subtype.isBlank() ? "." : "(" + subtype + ").");
    }
}
