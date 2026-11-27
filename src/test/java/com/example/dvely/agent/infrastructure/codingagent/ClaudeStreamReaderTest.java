package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Claude Code {@code stream-json} 줄 읽기(#600). 줄 모양은 고정한 2.1.316 에서 찍은 것이다.
 */
class ClaudeStreamReaderTest {

    private static final String KEY = "sk-ant-" + "test-0123456789abcdefghij";

    private final List<CodingAgentActivity> seen = new ArrayList<>();
    private final ClaudeStreamReader reader = new ClaudeStreamReader(seen::add, KEY);

    private static String assistant(String block) {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[" + block + "]}}";
    }

    private static String tool(String name, String input) {
        return assistant("{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"" + name + "\",\"input\":" + input + "}");
    }

    @Test
    void 도구는_작업_공간_기준_대상으로_말은_한_줄로() {
        reader.accept(tool("Read", "{\"file_path\":\"/workspace/app/src/App.jsx\"}"));
        reader.accept(tool("Edit", "{\"file_path\":\"/workspace/app/styles.css\",\"old_string\":\"x\",\"new_string\":\"y\"}"));
        reader.accept(tool("Write", "{\"file_path\":\"/workspace/notes.md\",\"content\":\"긴 내용\"}"));
        reader.accept(tool("Bash", "{\"command\":\"npm install\\nnpm run build\",\"description\":\"설치\"}"));
        reader.accept(tool("Grep", "{\"pattern\":\"btn-primary\"}"));
        reader.accept(assistant("{\"type\":\"text\",\"text\":\"**버튼** 규칙을\\n  `찾았습니다`.\"}"));
        reader.accept(tool("TodoWrite", "{\"todos\":[]}"));

        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.READ, "src/App.jsx"),
                new CodingAgentActivity(Kind.EDIT, "styles.css"),
                new CodingAgentActivity(Kind.EDIT, "notes.md"),
                new CodingAgentActivity(Kind.COMMAND, "npm install …"),
                new CodingAgentActivity(Kind.SEARCH, "btn-primary"),
                new CodingAgentActivity(Kind.MESSAGE, "버튼 규칙을 찾았습니다."));
    }

    @Test
    void 맺음말은_요약으로_보이니_진행_줄로_다시_넘기지_않는다() {
        reader.accept(assistant("{\"type\":\"text\",\"text\":\"스타일을 보겠습니다.\"}"));
        reader.accept(tool("Read", "{\"file_path\":\"/workspace/app/styles.css\"}"));
        reader.accept(assistant("{\"type\":\"text\",\"text\":\"저장 버튼만 키웠습니다.\"}"));
        reader.accept("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"저장 버튼만 키웠습니다.\"}");

        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.MESSAGE, "스타일을 보겠습니다."),
                new CodingAgentActivity(Kind.READ, "styles.css"));
        assertThat(reader.answer("")).isEqualTo("저장 버튼만 키웠습니다.");
    }

    @Test
    void 파일_내용과_도구_결과는_싣지_않는다() {
        reader.accept(tool("Write", "{\"file_path\":\"/workspace/app/a.js\",\"content\":\"const secret = 1\"}"));
        reader.accept("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"파일 전문\"}]}}");

        assertThat(seen).extracting(CodingAgentActivity::target).containsExactly("a.js");
    }

    @Test
    void 키는_명령에_나와도_가린다() {
        reader.accept(tool("Bash", "{\"command\":\"curl -H 'x-api-key: " + KEY + "' https://api.anthropic.com\"}"));
        reader.accept(tool("Bash", "{\"command\":\"echo ghp_abcdefghijklmnopqrstuvwxyz\"}"));

        assertThat(seen).extracting(CodingAgentActivity::target)
                .noneMatch(t -> t.contains(KEY) || t.contains("ghp_abcdefghij"))
                .anyMatch(t -> t.contains("x-api-key: ***"));
    }

    @Test
    void 답은_마지막_result_줄의_글이고_없으면_stdout_그대로() {
        assertThat(reader.answer("plain text")).isEqualTo("plain text");

        reader.accept("{\"duration_ms\":10,\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"다 했습니다.\"}");
        assertThat(reader.answer("{...}")).isEqualTo("다 했습니다.");

        reader.accept("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}");
        assertThat(reader.answer("{...}")).isEqualTo("Claude Code 가 남긴 말 없이 끝났습니다(error_max_turns).");
    }

    @Test
    void 모르는_줄과_깨진_줄은_넘긴다() {
        reader.accept("");
        reader.accept("Warning: something");
        reader.accept("{\"type\":\"assistant\",\"message\":");
        reader.accept("{\"type\":\"stream_event\",\"event\":{}}");
        reader.accept(tool("Read", "{}"));
        reader.accept(null);

        assertThat(seen).isEmpty();
        assertThat(reader.answer("raw")).isEqualTo("raw");
    }

    @Test
    void 긴_경로는_끝을_긴_명령은_앞을_남긴다() {
        String deep = "/workspace/app/" + "d/".repeat(120) + "Button.tsx";
        reader.accept(tool("Edit", "{\"file_path\":\"" + deep + "\"}"));
        reader.accept(tool("Bash", "{\"command\":\"echo " + "가".repeat(300) + "\"}"));

        assertThat(seen.get(0).target()).startsWith("…").endsWith("Button.tsx")
                .hasSize(ActivityEmitter.MAX_TARGET);
        assertThat(seen.get(1).target()).startsWith("echo 가").endsWith("…")
                .hasSize(ActivityEmitter.MAX_TARGET);
    }
}
