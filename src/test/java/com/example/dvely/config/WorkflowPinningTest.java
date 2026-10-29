package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * GitHub Actions 는 커밋 SHA 로만 참조한다(#511 B11). 태그(@v4)는 그 저장소 쪽에서 다른 커밋으로 옮겨질 수 있어,
 * 배포 키(EC2_SSH_KEY)와 npm 배포 권한이 있는 잡에서 남의 코드가 바뀐 채로 돌 수 있다.
 */
class WorkflowPinningTest {

    private static final Pattern USES = Pattern.compile("^\\s*-?\\s*uses:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern PINNED = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    @Test
    void everyActionIsPinnedToACommitSha() throws IOException {
        List<String> unpinned = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(Path.of(".github/workflows"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                Matcher m = USES.matcher(Files.readString(file));
                while (m.find()) {
                    checked++;
                    String ref = m.group(1);
                    if (!ref.startsWith("./") && !PINNED.matcher(ref).matches()) {
                        unpinned.add(file.getFileName() + ": " + ref);
                    }
                }
            }
        }
        assertThat(checked).isPositive();
        assertThat(unpinned).isEmpty();
    }
}
