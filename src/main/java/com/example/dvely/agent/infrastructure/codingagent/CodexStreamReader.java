package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex 의 {@code exec --json} 을 한 줄씩 읽는다(#600 PR-2). 실행 하나에 하나.
 *
 * <p>줄은 {@code thread.started} → {@code turn.started} → {@code item.started}/{@code item.completed} 여럿 →
 * {@code turn.completed}(또는 {@code turn.failed}) 순으로 온다. 한 일은 item 의 종류로 읽는다.</p>
 * <ul>
 *   <li>{@code command_execution} — 명령. {@code item.started} 에서 넘긴다(빌드처럼 오래 걸리는 명령이 도는 동안 보이게).
 *       명령은 {@code /bin/sh -lc '<명령>'} 로 감싸여 오므로 감싼 것을 벗긴다</li>
 *   <li>{@code file_change} — 파일 고치기·만들기({@code update}·{@code add})와 지우기({@code delete}). 한 item 에 파일 여럿</li>
 *   <li>{@code agent_message} — 말. Claude 와 같이 한 박자 늦게 넘긴다 — 다음 item 이 오면 중간 말,
 *       {@code turn.completed} 가 오면 맺음말이라 버리고 이 실행의 답으로 둔다. 예전 텍스트 출력(stdout)이 주던 것도
 *       바로 이 마지막 말이다</li>
 *   <li>{@code reasoning}(추론 요약)·{@code error}(경고)·{@code todo_list} 는 보이지 않는다. 명령 출력({@code aggregated_output})과
 *       파일 내용은 싣지 않는다</li>
 * </ul>
 *
 * <p>{@code --json} 에서는 실패 이유도 stdout 에 온다({@code turn.failed}, 그 앞의 {@code error} 줄). stderr 에는
 * "Reading additional input from stdin..." 만 남으므로 실패 이유는 {@link #failure()} 로 따로 넘긴다.</p>
 *
 * <p>모양은 고정한 판(docker/coding-agent/Dockerfile 의 0.153.2)을 가짜 Responses API 에 붙여 실제로 찍어 확인했다.
 * 운영 모델(gpt-5.6-luna)에서 Codex 는 도구를 JavaScript 한 칸({@code exec})으로 묶어 부르지만, 그 안의
 * {@code exec_command}·{@code apply_patch} 는 위 item 으로 하나씩 나온다. 모르는 줄은 넘긴다 — CLI 판이 바뀌어 모양이
 * 달라져도 진행 표시가 비는 것으로 그치고 실행은 그대로 간다. 운영자가 {@code argv-prefix} 에서 {@code --json} 을
 * 빼면 {@code turn.completed} 줄이 없으니 답은 stdout 그대로다.</p>
 */
final class CodexStreamReader implements AbstractCliCodingAgentAdapter.StreamReader {

    /** {@code /bin/sh -lc <한 낱말>}·{@code bash -c <한 낱말>} */
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:\\S*/)?(?:ba|z|da|k)?sh -l?c (.+)$", Pattern.DOTALL);

    private final ObjectMapper json = new ObjectMapper();
    private final ActivityEmitter emitter;
    /** {@code item.started} 에서 이미 넘긴 item — {@code item.completed} 에서 다시 넘기지 않는다 */
    private final Set<String> shown = new HashSet<>();
    private volatile String answer;
    private volatile String lastMessage;
    private volatile String failure;
    private volatile String lastError;
    /** 아직 중간 말인지 맺음말인지 모르는 마지막 말 */
    private String pendingText;

    /**
     * @param apiKey the run's own key, masked out of every target and failure (see {@link ActivityEmitter})
     */
    CodexStreamReader(Consumer<CodingAgentActivity> onActivity, String apiKey) {
        this.emitter = new ActivityEmitter(onActivity, apiKey);
    }

    @Override
    public void accept(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        if (!trimmed.startsWith("{")) {
            return;
        }
        JsonNode event;
        try {
            event = json.readTree(trimmed);
        } catch (Exception e) {
            return;
        }
        switch (event.path("type").asText("")) {
            case "item.started" -> started(event.path("item"));
            case "item.completed" -> completed(event.path("item"));
            case "turn.completed" -> {
                String closing = pendingText;
                pendingText = null;
                answer = closing != null ? closing : "Codex 가 남긴 말 없이 끝났습니다.";
            }
            case "turn.failed" -> {
                pendingText = null;
                String message = event.path("error").path("message").asText("");
                failure = emitter.masked(message.isBlank() ? lastError : message);
            }
            case "error" -> lastError = event.path("message").asText(null);
            default -> {
                // thread.started·turn.started 등은 보이지 않는다
            }
        }
    }

    /**
     * @param stdout the outcome's stdout, used when the stream never produced a closing message
     * @return the agent's closing text — the last thing it said, as the plain output used to be
     */
    @Override
    public String answer(String stdout) {
        if (answer != null) {
            return answer;
        }
        return lastMessage != null ? lastMessage : stdout;
    }

    @Override
    public String failure() {
        String reported = failure;
        return reported != null ? reported : emitter.masked(lastError);
    }

    private void started(JsonNode item) {
        String type = item.path("type").asText("");
        if ("command_execution".equals(type) || "file_change".equals(type)) {
            shown.add(item.path("id").asText(""));
            activityOf(item, type);
        }
    }

    private void completed(JsonNode item) {
        String type = item.path("type").asText("");
        switch (type) {
            case "agent_message" -> {
                flushText();
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    lastMessage = text;
                    pendingText = text;
                }
            }
            case "command_execution", "file_change" -> {
                if (!shown.remove(item.path("id").asText(""))) {
                    activityOf(item, type);
                }
            }
            case "web_search" -> {
                flushText();
                emitter.emit(Kind.SEARCH, ActivityEmitter.oneLine(item.path("query").asText("")));
            }
            default -> {
                // reasoning·error(경고)·todo_list 등
            }
        }
    }

    private void activityOf(JsonNode item, String type) {
        flushText();
        if ("command_execution".equals(type)) {
            emitter.emit(Kind.COMMAND, ActivityEmitter.firstLine(unwrapShell(item.path("command").asText(""))));
            return;
        }
        for (JsonNode change : item.path("changes")) {
            Kind kind = "delete".equals(change.path("kind").asText("")) ? Kind.DELETE : Kind.EDIT;
            emitter.emit(kind, ActivityEmitter.path(change.path("path").asText("")));
        }
    }

    private void flushText() {
        String text = pendingText;
        pendingText = null;
        if (text != null) {
            emitter.emit(Kind.MESSAGE, ActivityEmitter.plain(text));
        }
    }

    /**
     * Codex 가 감싼 셸({@code /bin/sh -lc '<명령>'})을 벗긴다. 감싼 부분이 셸 낱말 하나로 읽히지 않으면 그대로 둔다.
     *
     * <p>따옴표는 명령에 따라 다르게 붙는다(실측): 작은따옴표가 없으면 {@code 'cat index.html'}, 있으면
     * {@code "printf \"%s\\n\" \"it's\""} 나 {@code 'echo "x" && echo '"'y'"} 처럼 섞인다.</p>
     */
    static String unwrapShell(String command) {
        Matcher matcher = SHELL_WRAPPER.matcher(command.strip());
        if (!matcher.matches()) {
            return command;
        }
        String word = shellWord(matcher.group(1));
        return word == null ? command : word;
    }

    /** POSIX 셸 낱말 하나를 푼다. 따옴표가 닫히지 않았거나 낱말이 둘 이상이면 {@code null} */
    private static String shellWord(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                int end = text.indexOf('\'', i + 1);
                if (end < 0) {
                    return null;
                }
                out.append(text, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                i++;
                boolean closed = false;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < text.length() && "$`\"\\".indexOf(text.charAt(i + 1)) >= 0) {
                        out.append(text.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    out.append(d);
                    i++;
                }
                if (!closed) {
                    return null;
                }
            } else if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
            } else if (Character.isWhitespace(c)) {
                return null;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
