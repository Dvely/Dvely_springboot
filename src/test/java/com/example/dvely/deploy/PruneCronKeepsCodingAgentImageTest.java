package com.example.dvely.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 서버 정리 cron 이 코딩 에이전트 이미지를 지우지 않게 하는 약속의 두 쪽을 함께 지킨다(#616).
 *
 * <p>한쪽만 남으면 조용히 깨진다 — cron 의 {@code label!=qeploy.keep=true} 가 빠지면 이미지가 빌드 72시간 뒤 새벽에
 * 지워지고, Dockerfile 의 라벨이 빠지면 필터가 있어도 보호받지 못한다. 어느 쪽이든 증상은 며칠 뒤 BYOK 코딩 에이전트가
 * 전부 실패하는 것이라 원인과 멀다. 실제 정리 동작(라벨 붙은 이미지만 남고 나머지는 예전처럼 지워짐)은 Docker-in-Docker
 * 에서 확인했다(PR 본문).</p>
 */
class PruneCronKeepsCodingAgentImageTest {

    private static final String KEEP_LABEL = "qeploy.keep";

    @Test
    void theCodingAgentImageCarriesTheKeepLabel() throws Exception {
        String dockerfile = Files.readString(Path.of("docker/coding-agent/Dockerfile"));

        assertThat(dockerfile).containsPattern("(?m)^LABEL\\s+" + KEEP_LABEL.replace(".", "\\.") + "=\"true\"\\s*$");
    }

    @Test
    void theCleanupCronSkipsImagesWithTheKeepLabel() throws Exception {
        List<String> cronLines = Files.readAllLines(Path.of("deploy/setup-dev-server.sh")).stream()
                .filter(line -> line.startsWith("CRON=") && line.contains("prune"))
                .toList();

        assertThat(cronLines).isNotEmpty().allSatisfy(line -> {
            assertThat(line).contains("--filter \"label!=" + KEEP_LABEL + "=true\"");
            // -a 를 지키는 것도 의도다 — 빼면 배포 빌드가 남긴 태그 이미지가 쌓여 디스크가 다시 찬다.
            assertThat(line).contains("prune -af");
        });
    }
}
