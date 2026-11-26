package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.example.dvely.common.security.SecretRedactor;
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
final class ClaudeStreamReader implements Consumer<String> {

    static final int MAX_TARGET = 160;
    private static final String WORKSPACE_APP = "/workspace/app/";
    private static final String WORKSPACE = "/workspace/";

    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<CodingAgentActivity> onActivity;
    private final String apiKey;
    private volatile String answer;
    /** 아직 중간 말인지 맺음말인지 모르는 마지막 말 */
    private String pendingText;

    /**
     * @param apiKey the run's own key. Masked out of every target, on top of the shape-based
     *               {@link SecretRedactor} — the agent has the key in its environment, and a target
     *               is the first line of a command it chose to run
     */
    ClaudeStreamReader(Consumer<CodingAgentActivity> onActivity, String apiKey) {
        this.onActivity = onActivity;
        this.apiKey = apiKey;
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
    String answer(String stdout) {
        return answer != null ? answer : stdout;
    }

    private void activityOf(JsonNode block) {
        String type = block.path("type").asText("");
        if ("text".equals(type)) {
            flushText();
            pendingText = plain(block.path("text").asText(""));
            return;
        }
        if (!"tool_use".equals(type)) {
            return;
        }
        flushText();
        JsonNode input = block.path("input");
        switch (block.path("name").asText("")) {
            case "Read" -> emit(Kind.READ, path(input.path("file_path").asText("")));
            case "Edit", "MultiEdit", "Write" -> emit(Kind.EDIT, path(input.path("file_path").asText("")));
            case "NotebookEdit" -> emit(Kind.EDIT, path(input.path("notebook_path").asText("")));
            case "Bash" -> emit(Kind.COMMAND, firstLine(input.path("command").asText("")));
            case "Grep", "Glob" -> emit(Kind.SEARCH, oneLine(input.path("pattern").asText("")));
            default -> {
                // 할 일 목록·하위 에이전트 등은 화면에 보일 대상이 없다
            }
        }
    }

    private void flushText() {
        String text = pendingText;
        pendingText = null;
        if (text != null) {
            emit(Kind.MESSAGE, text);
        }
    }

    private void emit(Kind kind, String target) {
        String safe = masked(target);
        if (safe == null || safe.isBlank()) {
            return;
        }
        onActivity.accept(new CodingAgentActivity(kind, clip(safe, kind)));
    }

    private String masked(String text) {
        if (text == null) {
            return null;
        }
        String out = apiKey == null || apiKey.isBlank() ? text : text.replace(apiKey, "***");
        return SecretRedactor.redact(out);
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

    /** 작업 공간 기준 경로. 프로젝트는 /workspace/app 에 있다 */
    private static String path(String value) {
        if (value.startsWith(WORKSPACE_APP)) {
            return value.substring(WORKSPACE_APP.length());
        }
        if (value.startsWith(WORKSPACE)) {
            return value.substring(WORKSPACE.length());
        }
        return value;
    }

    private static String firstLine(String value) {
        String stripped = value.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline).strip() + " …";
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /** 화면은 글을 그대로 그린다. 마크다운 강조·코드 표시는 기호만 남으니 걷어 낸다 */
    private static String plain(String value) {
        return oneLine(value.replace("**", "").replace("`", "").replaceAll("(?m)^#+\\s*", ""));
    }

    /** 경로는 끝(파일 이름)이, 명령·말은 앞이 중요하다 */
    private static String clip(String value, Kind kind) {
        if (value.codePointCount(0, value.length()) <= MAX_TARGET) {
            return value;
        }
        if (kind == Kind.READ || kind == Kind.EDIT) {
            int from = value.offsetByCodePoints(value.length(), -(MAX_TARGET - 1));
            return "…" + value.substring(from);
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_TARGET - 1)) + "…";
    }
}
