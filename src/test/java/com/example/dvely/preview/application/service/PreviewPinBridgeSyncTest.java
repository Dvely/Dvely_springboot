package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 프리뷰 짚기 브리지의 {@code inspectPoint} 가 CLI 의 것과 글자까지 같은지 본다(설계 §16.4).
 *
 * <p>둘은 같은 규칙(요소·범위 사다리·영역·빈 자리)으로 페이지를 읽어야 한다. 기기 공유로 짚은 것과
 * 서버 프리뷰에서 짚은 것이 같은 화면에서 다른 범위를 내면, 사람이 보는 칩과 에이전트가 받는 글이
 * 어긋난다. CLI 쪽({@code agent-tools/packages/cli/src/share/pin-web.js})만 고치고 이쪽을 잊으면
 * 여기서 깨진다. 고칠 때는 CLI 의 함수 본문을 브리지의 BEGIN~END 사이에 그대로 옮긴다.</p>
 */
class PreviewPinBridgeSyncTest {

    private static final Path CLI = Path.of("agent-tools/packages/cli/src/share/pin-web.js");
    private static final Path BRIDGE = Path.of("src/main/resources/preview/pin-bridge.js");

    @Test
    void inspectPointIsTheCliFunctionLetterForLetter() throws IOException {
        String cli = Files.readString(CLI, StandardCharsets.UTF_8);
        int start = cli.indexOf("export function inspectPoint(args) {");
        assertThat(start).as("CLI 에 inspectPoint 가 있어야 한다").isNotNegative();
        // 함수는 맨 앞 칸의 닫는 중괄호 한 줄로 끝난다.
        int end = cli.indexOf("\n}\n", start);
        assertThat(end).isPositive();
        String fromCli = cli.substring(start + "export ".length(), end + 2);

        String bridge = Files.readString(BRIDGE, StandardCharsets.UTF_8);
        int begin = bridge.indexOf("// BEGIN inspectPoint\n");
        int stop = bridge.indexOf("\n  // END inspectPoint");
        assertThat(begin).isNotNegative();
        assertThat(stop).isGreaterThan(begin);
        String inBridge = bridge.substring(begin + "// BEGIN inspectPoint\n".length(), stop);

        assertThat(inBridge).isEqualTo(fromCli);
    }

    @Test
    void theBridgeOnTheClasspathIsThisFile() throws IOException {
        assertThat(PreviewGatewayService.PIN_BRIDGE)
                .isEqualTo(Files.readString(BRIDGE, StandardCharsets.UTF_8));
    }
}
