package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.example.dvely.common.security.SecretRedactor;
import java.util.function.Consumer;

/**
 * 두 CLI 의 줄 읽기({@link ClaudeStreamReader}·{@link CodexStreamReader})가 한 일을 넘기는 공통 부분(#600).
 *
 * <p>줄 모양은 CLI 마다 다르지만, 넘기기 전에 할 일은 같다. 실행의 키를 그대로 가리고 {@link SecretRedactor} 를
 * 거친 뒤, 화면 한 줄에 맞게 자른다. 둘이 따로 가지면 한쪽만 고쳐져 가리는 규칙이 갈라진다.</p>
 */
final class ActivityEmitter {

    static final int MAX_TARGET = 160;
    private static final String WORKSPACE_APP = "/workspace/app/";
    private static final String WORKSPACE = "/workspace/";

    private final Consumer<CodingAgentActivity> onActivity;
    private final String apiKey;

    /**
     * @param apiKey the run's own key. Masked out of every target, on top of the shape-based
     *               {@link SecretRedactor} — the agent has the key in its environment, and a target
     *               is the first line of a command it chose to run
     */
    ActivityEmitter(Consumer<CodingAgentActivity> onActivity, String apiKey) {
        this.onActivity = onActivity;
        this.apiKey = apiKey;
    }

    void emit(Kind kind, String target) {
        String safe = masked(target);
        if (safe == null || safe.isBlank()) {
            return;
        }
        onActivity.accept(new CodingAgentActivity(kind, clip(safe, kind)));
    }

    String masked(String text) {
        if (text == null) {
            return null;
        }
        String out = apiKey == null || apiKey.isBlank() ? text : text.replace(apiKey, "***");
        return SecretRedactor.redact(out);
    }

    /** 작업 공간 기준 경로. 프로젝트는 /workspace/app 에 있다 */
    static String path(String value) {
        if (value.startsWith(WORKSPACE_APP)) {
            return value.substring(WORKSPACE_APP.length());
        }
        if (value.startsWith(WORKSPACE)) {
            return value.substring(WORKSPACE.length());
        }
        return value;
    }

    static String firstLine(String value) {
        String stripped = value.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline).strip() + " …";
    }

    static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /** 화면은 글을 그대로 그린다. 마크다운 강조·코드 표시는 기호만 남으니 걷어 낸다 */
    static String plain(String value) {
        return oneLine(value.replace("**", "").replace("`", "").replaceAll("(?m)^#+\\s*", ""));
    }

    /** 경로는 끝(파일 이름)이, 명령·말은 앞이 중요하다 */
    static String clip(String value, Kind kind) {
        if (value.codePointCount(0, value.length()) <= MAX_TARGET) {
            return value;
        }
        if (kind == Kind.READ || kind == Kind.EDIT || kind == Kind.DELETE) {
            int from = value.offsetByCodePoints(value.length(), -(MAX_TARGET - 1));
            return "…" + value.substring(from);
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_TARGET - 1)) + "…";
    }
}
