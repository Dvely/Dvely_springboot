package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨테이너 자격 파일의 수명을 고정한다 (#413).
 *
 * <p>고쳐야 했던 것은 "쓴다" 가 아니라 <b>"지운다"</b> 다. 세 호출 지점이 모두 쓰기만 하고
 * 지우지 않아서, 사용자 GitHub OAuth 토큰이 평문으로 컨테이너 수명 내내 남았다.</p>
 */
class ContainerGitCredentialsTest {

    private static final String CONTAINER = "c1";
    private static final String TOKEN = "gho_supersecrettoken";

    private record Fixture(ContainerGitCredentials credentials, List<String> commands) {}

    private Fixture fixture() {
        DockerContainerService docker = mock(DockerContainerService.class);
        List<String> commands = new ArrayList<>();
        when(docker.exec(eq(CONTAINER), anyString())).thenAnswer(i -> {
            commands.add(i.getArgument(1));
            return "";
        });
        return new Fixture(new ContainerGitCredentials(docker), commands);
    }

    @Test
    @DisplayName("작업이 성공하면 자격 파일과 helper 설정을 지운다")
    void clearsCredentialsAfterSuccess() {
        Fixture f = fixture();

        f.credentials().withCredentials(CONTAINER, "octocat", TOKEN, () -> { });

        assertThat(f.commands())
                .anySatisfy(c -> assertThat(c).contains("rm -f /tmp/.git-credentials"))
                .anySatisfy(c -> assertThat(c).contains("--unset credential.helper"));
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 자격을 지운다 — 여기가 이 클래스의 존재 이유다")
    void clearsCredentialsEvenWhenWorkThrows() {
        // git push 는 인증 실패·보호 브랜치·머지 충돌로 실제로 던진다. 그때 자격이 남으면
        // 정리를 호출부에 맡긴 예전 구조와 다를 바가 없다.
        Fixture f = fixture();

        assertThatThrownBy(() -> f.credentials().withCredentials(CONTAINER, "octocat", TOKEN, () -> {
            throw new IllegalStateException("push 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("push 실패");

        assertThat(f.commands()).anySatisfy(c -> assertThat(c).contains("rm -f /tmp/.git-credentials"));
    }

    @Test
    @DisplayName("작업은 자격이 있는 동안 돈다 — 쓰기가 먼저, 지우기가 나중")
    void workRunsBetweenWriteAndClear() {
        Fixture f = fixture();
        List<String> order = new ArrayList<>();

        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.exec(eq(CONTAINER), anyString())).thenAnswer(i -> {
            String cmd = i.getArgument(1);
            if (cmd.contains("writeFileSync")) order.add("write");
            if (cmd.contains("rm -f")) order.add("clear");
            return "";
        });
        new ContainerGitCredentials(docker)
                .withCredentials(CONTAINER, "octocat", TOKEN, () -> order.add("work"));

        // 순서가 뒤집히면 인증이 필요한 명령이 자격 없이 돈다.
        assertThat(order).containsExactly("write", "work", "clear");
    }

    @Test
    @DisplayName("토큰은 base64 로만 명령에 들어간다 — 평문이 명령줄에 남지 않는다")
    void tokenNeverAppearsInPlaintextInAnyCommand() {
        // ps·docker exec 로그에 명령 문자열이 남는다. base64 는 암호화가 아니지만, 평문 토큰이
        // 그대로 찍히는 것과는 다르다 — 원래 설계 의도이고 이 수정이 그것을 깨지 않았음을 본다.
        Fixture f = fixture();

        f.credentials().withCredentials(CONTAINER, "octocat", TOKEN, () -> { });

        assertThat(f.commands()).noneSatisfy(c -> assertThat(c).contains(TOKEN));
    }

    @Test
    @DisplayName("정리가 실패해도 던지지 않는다 — git 작업의 실제 실패 원인을 덮지 않는다")
    void clearFailureIsSwallowed() {
        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.exec(eq(CONTAINER), anyString())).thenReturn("");
        doThrow(new RuntimeException("컨테이너가 이미 죽었다"))
                .when(docker).exec(eq(CONTAINER), org.mockito.ArgumentMatchers.contains("rm -f"));

        ContainerGitCredentials credentials = new ContainerGitCredentials(docker);

        // 성공 경로: 정리 실패가 성공을 실패로 뒤집지 않는다.
        credentials.withCredentials(CONTAINER, "octocat", TOKEN, () -> { });

        // 실패 경로: 원래 예외가 정리 실패로 가려지지 않는다.
        assertThatThrownBy(() -> credentials.withCredentials(CONTAINER, "octocat", TOKEN, () -> {
            throw new IllegalStateException("진짜 원인");
        })).isInstanceOf(IllegalStateException.class).hasMessage("진짜 원인");

        verify(docker, org.mockito.Mockito.atLeastOnce())
                .exec(eq(CONTAINER), org.mockito.ArgumentMatchers.contains("rm -f"));
    }
}
