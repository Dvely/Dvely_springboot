package com.example.dvely.project.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepositoryNamePolicyTest {

    @Test
    void lowercasesAndHyphenatesTheProjectName() {
        assertThat(RepositoryNamePolicy.forProject("My Cool Project", 1L)).isEqualTo("my-cool-project");
    }

    @Test
    void collapsesHyphenRunsAndTrimsTheEdges() {
        assertThat(RepositoryNamePolicy.forProject("__Hello__World__", 1L)).isEqualTo("hello-world");
    }

    @Test
    void fallsBackToAUserScopedNameWhenNothingSurvivesSanitising() {
        // 한글만 있는 이름은 정규화하면 아무것도 남지 않는다. 빈 이름으로 GitHub 생성 API 를 부르면
        // 실패하므로 유저별 폴백으로 떨어져야 한다.
        assertThat(RepositoryNamePolicy.forProject("동미대", 7L)).isEqualTo("qeploy-project-7");
    }

    @Test
    void sanitizeReturnsEmptyRatherThanFallingBack() {
        // 폴백 여부는 호출부가 정한다. 승인 본문으로 들어온 이름이 전부 걸러졌을 때 게이트가 보여준
        // 후보로 되돌아갈 수 있어야 하기 때문이다.
        assertThat(RepositoryNamePolicy.sanitize("!!!")).isEmpty();
        assertThat(RepositoryNamePolicy.sanitize(null)).isEmpty();
        assertThat(RepositoryNamePolicy.sanitize("  ")).isEmpty();
    }

    @Test
    void keepsDigitsAndExistingHyphens() {
        assertThat(RepositoryNamePolicy.sanitize("todo-kanban-2")).isEqualTo("todo-kanban-2");
    }

    @Test
    void lowercasesWithRootLocaleSoTurkishServersProduceTheSameName() {
        // 기본 로케일로 소문자화하면 터키어 환경에서 I 가 ı 로 바뀌어 서버마다 다른 저장소 이름이
        // 나온다. 그러면 한 경로가 만든 저장소를 다른 경로가 찾지 못한다.
        assertThat(RepositoryNamePolicy.sanitize("MY-PROJECT-I")).isEqualTo("my-project-i");
    }
}
