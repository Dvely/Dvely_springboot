package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 토큰이 <b>명령 문자열에 절대 들어가지 않는다</b>는 것을 고정한다 (#413).
 *
 * <p>인라인 헬퍼가 실제로 git 에서 동작하는지는 단위 테스트로 알 수 없다 — 그건
 * {@code node:20-alpine} + git 2.52 컨테이너에서 {@code git credential fill} 로 실측했다
 * (PR 본문 참고). 여기서 지키는 것은 <b>토큰이 어디로 가고 어디로 가지 않는가</b>다.</p>
 */
class ContainerGitCredentialsTest {

    private static final String CONTAINER = "c1";
    private static final String USER = "octocat";
    private static final String TOKEN = "gho_supersecrettoken";

    private record Fixture(ContainerGitCredentials credentials, DockerContainerService docker) {}

    private Fixture fixture() {
        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.execWithExitCode(eq(CONTAINER), anyString(), any()))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));
        return new Fixture(new ContainerGitCredentials(docker), docker);
    }

    @Test
    @DisplayName("토큰은 명령이 아니라 exec env 로만 간다 — 명령 문자열에 평문이 없다")
    void tokenTravelsOnlyThroughExecEnv() {
        // 여기가 이 클래스의 존재 이유다. 명령 문자열은 log.debug 와 예외 메시지에 남고, 예전에는
        // 그 문자열에 base64 토큰이 들어 있었다(base64 는 암호화가 아니다). 이제 아예 안 들어간다.
        Fixture f = fixture();

        f.credentials().exec(CONTAINER, USER, TOKEN, "cd /workspace/app && git push -u origin preview");

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(f.docker()).execWithExitCode(eq(CONTAINER), cmd.capture(), env.capture());

        assertThat(cmd.getValue())
                .doesNotContain(TOKEN)
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(TOKEN.getBytes()));
        assertThat(env.getValue()).contains("QEPLOY_GIT_TOKEN=" + TOKEN, "QEPLOY_GIT_USER=" + USER);
    }

    @Test
    @DisplayName("자격 파일을 만들지 않는다 — /tmp/.git-credentials 가 어느 명령에도 없다")
    void neverWritesACredentialsFile() {
        Fixture f = fixture();

        f.credentials().exec(CONTAINER, USER, TOKEN, "git clone https://github.com/o/r.git /workspace/app");

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(f.docker()).execWithExitCode(eq(CONTAINER), cmd.capture(), any());
        assertThat(cmd.getValue())
                .doesNotContain("/tmp/.git-credentials")
                .doesNotContain("writeFileSync");
    }

    @Test
    @DisplayName("helper 는 git 바로 뒤에 끼워진다 — cd 로 시작하는 명령도 형태가 유지된다")
    void insertsHelperRightAfterGit() {
        ContainerGitCredentials c = new ContainerGitCredentials(mock(DockerContainerService.class));

        assertThat(c.authed("git fetch origin"))
                .startsWith("git -c credential.helper='!f()")
                .endsWith(" fetch origin");
        assertThat(c.authed("cd /workspace/app && git push -u origin preview"))
                .startsWith("cd /workspace/app && git -c credential.helper='!f()")
                .endsWith(" push -u origin preview");
        // GIT_TERMINAL_PROMPT=0 접두는 유지돼야 한다 — 없으면 인증 실패 시 프롬프트로 멈춘다.
        assertThat(c.authed("GIT_TERMINAL_PROMPT=0 git clone https://x/y.git /app"))
                .startsWith("GIT_TERMINAL_PROMPT=0 git -c credential.helper='!f()");
    }

    @Test
    @DisplayName("헬퍼 문자열에 홑따옴표가 없다 — 있으면 바깥 인용이 깨져 조용히 실패한다")
    void helperContainsNoSingleQuote() {
        // 바깥 명령이 helper 를 홑따옴표로 감싸므로, 안에 홑따옴표가 있으면 셸 인용이 끊긴다.
        // 그 결과는 예외가 아니라 '자격을 못 받은 git' 이라 조용히 실패한다.
        String authed = new ContainerGitCredentials(mock(DockerContainerService.class))
                .authed("git fetch origin");
        String helper = authed.substring(authed.indexOf('\'') + 1, authed.lastIndexOf('\''));

        assertThat(helper).doesNotContain("'");
        // env 이름이 헬퍼와 env() 양쪽에서 같아야 한다 — 어긋나면 빈 자격이 나간다.
        assertThat(helper).contains("$QEPLOY_GIT_USER", "$QEPLOY_GIT_TOKEN");
    }

    @Test
    @DisplayName("git 명령이 아니면 거절한다 — 자격을 엉뚱한 명령에 붙이지 않는다")
    void refusesNonGitCommands() {
        ContainerGitCredentials c = new ContainerGitCredentials(mock(DockerContainerService.class));

        assertThatThrownBy(() -> c.authed("npm install"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("npm install");
    }

    @Test
    @DisplayName("env 이름이 헬퍼와 짝이 맞는다 — 한쪽만 바뀌면 빈 자격이 나간다")
    void envNamesMatchTheHelper() {
        ContainerGitCredentials c = new ContainerGitCredentials(mock(DockerContainerService.class));

        assertThat(c.env(USER, TOKEN))
                .containsExactly("QEPLOY_GIT_USER=" + USER, "QEPLOY_GIT_TOKEN=" + TOKEN);
    }
}
