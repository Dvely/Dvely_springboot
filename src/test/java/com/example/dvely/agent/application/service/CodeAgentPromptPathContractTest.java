package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 코드 에이전트에게 <b>말하는 경로</b>와 그 뒤가 <b>실제로 보는 경로</b>가 같은지 지킨다.
 *
 * <p>둘이 어긋나면 에이전트가 엉뚱한 곳에 파일을 만들고 push·diff·배포가 전부 깨진다. 실제로 그
 * 계열로 두 번 터졌다 — 프레임워크 없는 프로젝트에서 스캐폴더가 안 돌아 코드가 {@code /workspace}
 * 루트에 쌓였고(#303), 프리뷰는 index.html 폴백 덕에 <b>화면이 멀쩡히 동작해</b> 승인 단계에
 * 와서야 드러났다.</p>
 *
 * <p>프롬프트는 모델이 읽는 텍스트 블록이라 상수를 끼워 넣으면 읽기 나빠진다. 그래서 리터럴로
 * 두되, 값이 갈라지는 것은 여기서 잡는다 — 사람이 두 곳을 같이 고치는 것에 기대지 않는다.</p>
 */
class CodeAgentPromptPathContractTest {

    @Test
    void systemPromptPointsAtTheSameAppDirectoryTheCodeUses() {
        String prompt = (String) ReflectionTestUtils.getField(CodeAgentService.class, "SYSTEM_PROMPT");

        assertThat(prompt).isNotNull();
        // 상위 디렉터리만 말하고 끝나면 코드가 /workspace 루트에 쌓인다 — 앱 경로를 반드시 짚어야 한다.
        assertThat(prompt).contains("MUST live in " + ContainerPaths.APP_DIR);
        // 빌드도 같은 곳에서 돌아야 한다.
        assertThat(prompt).contains(ContainerPaths.inApp("npm run build"));
    }
}
