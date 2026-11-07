package com.example.dvely.agent.application.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLogTextTest {

    private static final String SECRET = "qeploy-fake-secret-DO-NOT-LOG";

    @Test
    void writeFileKeepsPathAndLengthButNeverTheContent() {
        String content = "OPENAI_API_KEY=" + SECRET + "\n";

        String summary = AgentLogText.toolInput("write_file",
                Map.of("path", "/workspace/app/.env", "content", content));

        assertThat(summary)
                .isEqualTo("path=/workspace/app/.env content=" + content.length() + "자")
                .doesNotContain(SECRET);
    }

    @Test
    void readFileResultIsTheFileItselfSoOnlyItsLengthIsLogged() {
        String fileContent = "DB_PASSWORD=" + SECRET;

        assertThat(AgentLogText.toolResult("read_file", fileContent))
                .isEqualTo("(" + fileContent.length() + "자)");
    }

    @Test
    void commandAndCommandOutputAreClippedTo300Chars() {
        String longCommand = "echo " + "a".repeat(1_000);

        String input = AgentLogText.toolInput("execute_command", Map.of("command", longCommand));
        String result = AgentLogText.toolResult("execute_command", "b".repeat(1_000));

        assertThat(input).startsWith("command=echo ").contains("…(총 " + longCommand.length() + "자)");
        assertThat(input.length()).isLessThan("command=".length() + AgentLogText.COMMAND_MAX_CHARS + 20);
        assertThat(result).startsWith("b".repeat(AgentLogText.COMMAND_MAX_CHARS)).endsWith("…(총 1000자)");
    }

    @Test
    void aShorterCapStillReportsTheOriginalLength() {
        // 요약을 한 번 더 자르면 "(총 N자)" 가 요약의 길이가 된다. 상한을 직접 받아 원래 길이를 지킨다.
        String longCommand = "echo " + "a".repeat(1_000);

        String trace = AgentLogText.toolInput("execute_command", Map.of("command", longCommand), 120, 120);

        assertThat(trace).isEqualTo("command=" + longCommand.substring(0, 120) + "…(총 " + longCommand.length() + "자)");
    }

    @Test
    void unknownToolLogsArgumentNamesOnly() {
        assertThat(AgentLogText.toolInput("mystery_tool", Map.of("token", SECRET, "alpha", "x")))
                .isEqualTo("args=[alpha, token]");
    }

    @Test
    void missingOrMalformedArgumentsDoNotThrow() {
        Map<String, Object> nonString = new HashMap<>();
        nonString.put("path", 42);
        nonString.put("content", null);

        assertThat(AgentLogText.toolInput("write_file", null)).isEqualTo("path=(없음) content=(없음)");
        assertThat(AgentLogText.toolInput("write_file", nonString)).isEqualTo("path=(없음) content=(없음)");
        assertThat(AgentLogText.toolInput(null, Map.of())).isEqualTo("args=[]");
        assertThat(AgentLogText.toolResult("execute_command", null)).isEmpty();
    }

    @Test
    void instructionKeepsTheTotalLengthAndAtMost500Chars() {
        String instruction = "가".repeat(2_000) + SECRET;

        String logged = AgentLogText.instruction(instruction);

        assertThat(logged)
                .startsWith("(" + instruction.length() + "자) ")
                .doesNotContain(SECRET);
        assertThat(logged.length()).isLessThan(AgentLogText.INSTRUCTION_MAX_CHARS + 40);
        assertThat(AgentLogText.instruction("짧은 지시")).isEqualTo("(5자) 짧은 지시");
        assertThat(AgentLogText.instruction(null)).isEqualTo("(0자) ");
    }

    @Test
    void clipNeverSplitsASurrogatePair() {
        // "😀" 는 서로게이트 쌍(2 char)이다. 3번째 char 에서 자르면 쌍의 앞 절반만 남는다.
        String clipped = AgentLogText.clip("ab😀cd", 3);

        assertThat(clipped).startsWith("ab…");
        assertThat(clipped.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
    }
}
