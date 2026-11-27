package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Codex {@code exec --json} 줄 읽기(#600 PR-2).
 *
 * <p>자료(src/test/resources/codingagent/codex-0.153.2)는 고정한 0.153.2 를 이미지 그대로 돌려 찍은 stdout 이다.
 * 모델 자리에는 가짜 Responses API 를 붙여(키 없이) 도구 호출을 정해 두었고, 모델 이름은 운영 기본값(gpt-5.6-luna)이다 —
 * 그 모델에서 Codex 는 도구를 JavaScript 한 칸({@code exec})으로 묶어 부르므로, 그 안의 명령·패치가 item 으로 하나씩
 * 나오는지까지 본 것이다.</p>
 */
class CodexStreamReaderTest {

    private static final String KEY = "sk-proj-" + "test0123456789abcdefghijklmnopqrstuv";

    private final List<CodingAgentActivity> seen = new ArrayList<>();
    private final CodexStreamReader reader = new CodexStreamReader(seen::add, KEY);

    private static List<String> captured(String name) {
        try (InputStream in = CodexStreamReaderTest.class.getResourceAsStream("/codingagent/codex-0.153.2/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void feed(List<String> lines) {
        lines.forEach(reader);
    }

    @Test
    void 명령과_파일_변경을_한_번씩_넘기고_마지막_말을_답으로_둔다() {
        feed(captured("tools.jsonl"));

        // 추론 요약은 넘기지 않는다. 명령은 감싼 셸을 벗기고, 두 줄짜리는 첫 줄만. 실패한 명령(exit 1)도 한 일이다
        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.MESSAGE, "먼저 index.html 을 보겠습니다."),
                new CodingAgentActivity(Kind.COMMAND, "cat index.html"),
                new CodingAgentActivity(Kind.EDIT, "index.html"),
                new CodingAgentActivity(Kind.EDIT, "src/styles.css"),
                new CodingAgentActivity(Kind.COMMAND, "grep -rn navy src"),
                new CodingAgentActivity(Kind.COMMAND, "echo one && …"),
                new CodingAgentActivity(Kind.COMMAND, "ls nope-dir"));
        // 맺음말은 요약으로 보이므로 진행 줄로 다시 넘기지 않고, 예전 텍스트 출력처럼 꾸밈을 그대로 둔 채 답이 된다
        assertThat(reader.answer("raw")).isEqualTo("제목을 **안녕**으로 바꾸고 남색 스타일을 더했습니다.");
        assertThat(reader.failure()).isNull();
    }

    @Test
    void 한_패치의_파일_여럿을_따로_넘기고_지운_파일은_지우기로() {
        feed(captured("delete.jsonl"));

        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.EDIT, "index.html"),
                new CodingAgentActivity(Kind.DELETE, "src/styles.css"));
        assertThat(reader.answer("")).isEqualTo("끝");
    }

    @Test
    void 감싼_셸은_따옴표가_섞여도_명령_그대로_벗긴다() {
        feed(captured("quoting.jsonl"));

        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.COMMAND, "printf \"%s\\n\" \"it's\""),
                new CodingAgentActivity(Kind.COMMAND, "echo \"x $HOME\" && echo 'y'"));
    }

    @Test
    void 셸_낱말_하나로_읽히지_않으면_감싼_채로_둔다() {
        assertThat(CodexStreamReader.unwrapShell("bash -c 'npm test'")).isEqualTo("npm test");
        assertThat(CodexStreamReader.unwrapShell("/usr/bin/bash -lc npm\\ test")).isEqualTo("npm test");
        assertThat(CodexStreamReader.unwrapShell("/bin/sh -lc 'unclosed")).isEqualTo("/bin/sh -lc 'unclosed");
        assertThat(CodexStreamReader.unwrapShell("/bin/sh -lc npm test")).isEqualTo("/bin/sh -lc npm test");
        assertThat(CodexStreamReader.unwrapShell("python3 build.py")).isEqualTo("python3 build.py");
    }

    @Test
    void 실패하면_스트림의_실패_이유를_넘기고_진행_줄은_남기지_않는다() {
        // 실측: 키가 거절되면 다시 잇기 다섯 번(error 줄) 뒤 turn.failed 로 끝나고 exit 1. stderr 에는
        // "Reading additional input from stdin..." 만 남는다
        feed(captured("rejected-key.jsonl"));

        assertThat(seen).isEmpty();
        assertThat(reader.failure())
                .startsWith("unexpected status 401 Unauthorized: Incorrect API key provided")
                .doesNotContain("Reconnecting");
        // 말이 하나도 없었으니 답은 받은 stdout 그대로다(실패 이유는 따로 간다)
        assertThat(reader.answer("raw")).isEqualTo("raw");
    }

    @Test
    void 끝_줄_없이_끊기면_마지막_error_줄을_실패_이유로_한다() {
        reader.accept("{\"type\":\"turn.started\"}");
        reader.accept("{\"type\":\"error\",\"message\":\"stream disconnected before completion\"}");

        assertThat(reader.failure()).isEqualTo("stream disconnected before completion");
    }

    @Test
    void 대상과_실패_이유에서_실행의_키를_가린다() {
        reader.accept("{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\","
                + "\"command\":\"/bin/sh -lc 'echo " + KEY + "'\",\"aggregated_output\":\"\",\"status\":\"in_progress\"}}");
        reader.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"bad key " + KEY + "\"}}");

        assertThat(seen).singleElement().satisfies(activity -> assertThat(activity.target()).doesNotContain(KEY));
        assertThat(reader.failure()).doesNotContain(KEY);
    }

    @Test
    void 끝났다는_줄만_있으면_말_없이_끝났다고_답한다() {
        reader.accept("{\"type\":\"turn.started\"}");
        reader.accept("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100}}");

        assertThat(reader.answer("")).isEqualTo("Codex 가 남긴 말 없이 끝났습니다.");
    }

    @Test
    void 시작_줄이_없던_item_은_끝_줄에서_넘기고_모르는_줄은_넘긴다() {
        reader.accept("not json");
        reader.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_9\",\"type\":\"todo_list\",\"items\":[]}}");
        reader.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_9\",\"type\":\"command_execution\","
                + "\"command\":\"npm run build\",\"exit_code\":0,\"status\":\"completed\"}}");
        reader.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_8\",\"type\":\"web_search\",\"query\":\"vite  base path\"}}");

        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.COMMAND, "npm run build"),
                new CodingAgentActivity(Kind.SEARCH, "vite base path"));
    }

    @Test
    void json_을_빼고_돌리면_답은_stdout_그대로다() {
        // 운영자가 argv-prefix 에서 --json 을 빼면 stdout 은 마지막 말 그 자체다
        reader.accept("제목을 바꿨습니다.");

        assertThat(seen).isEmpty();
        assertThat(reader.answer("제목을 바꿨습니다.")).isEqualTo("제목을 바꿨습니다.");
    }
}
