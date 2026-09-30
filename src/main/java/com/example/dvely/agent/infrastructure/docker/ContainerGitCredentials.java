package com.example.dvely.agent.infrastructure.docker;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 안에서 git 인증이 필요한 구간에만 자격 파일을 두고, 끝나면 지운다 (#413).
 *
 * <h2>왜 이 클래스가 있나</h2>
 * 세 곳(프리뷰 워크스페이스 clone, preview 브랜치 push, 백엔드 배포 clone)이 각자
 * {@code /tmp/.git-credentials} 를 쓰고 <b>아무도 지우지 않았다</b>. 그 파일에는
 * <b>사용자의 GitHub OAuth 액세스 토큰이 평문으로</b> 들어 있고, 컨테이너 수명(TTL 기본 30분,
 * 접근마다 연장, 승인 hold 로 최대 6시간) 내내 남았다.
 *
 * <p>그 컨테이너는 {@code npm install} 로 받은 의존성과 에이전트가 쓴 앱 코드를 돈다. 파일을
 * 쓰는 uid 와 앱을 돌리는 uid 가 같다({@code node}) — 그래서 그 코드가
 * {@code cat /tmp/.git-credentials} 로 읽을 수 있었다. 게다가
 * {@code qeploy.preview.egress.enabled} 가 기본 {@code false} 라(코딩 에이전트 쪽은 {@code true})
 * 읽은 값을 밖으로 보내는 것을 막는 것도 없다.</p>
 *
 * <h2>왜 write/clear 를 따로 내놓지 않나</h2>
 * 세 곳이 각자 지우게 하면 한 곳을 잊는다 — 애초에 세 곳이 모두 잊어서 이 이슈가 생겼다.
 * {@link #withCredentials} 하나만 내놓아서 <b>정리를 건너뛸 방법이 없게</b> 한다. git 작업이
 * 예외로 끝나도 {@code finally} 가 지운다.
 *
 * <h2>남는 창</h2>
 * git 작업이 도는 동안(초 단위)에는 파일이 존재한다. 그 구간을 없애려면 파일을 아예 두지 않고
 * {@code credential.helper} 를 env 기반 인라인 헬퍼로 바꿔야 하는데, 그것이 실제로 인증되는지는
 * 실 컨테이너에서만 확인된다 — 틀리면 clone·push 가 전부 깨진다. 검증할 수 있는 것부터 한다.
 * 후속은 #413 코멘트 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerGitCredentials {

    /** git 이 읽는 자격 파일. helper 설정과 이 경로가 함께 움직여야 한다. */
    private static final String CREDENTIALS_FILE = "/tmp/.git-credentials";

    private final DockerContainerService dockerService;

    /**
     * git 작업을 자격이 있는 구간 안에서 돌린다. 끝나면 — 예외로 끝나도 — 자격을 지운다.
     *
     * @param username GitHub 사용자명. 토큰과 함께 자격 파일에만 들어간다
     * @param userToken 사용자 GitHub OAuth 액세스 토큰. <b>명령줄·로그에 넣지 않는다</b>
     * @param gitWork clone·fetch·push 등 인증이 필요한 작업
     */
    public void withCredentials(String containerId, String username, String userToken, Runnable gitWork) {
        write(containerId, username, userToken);
        try {
            gitWork.run();
        } finally {
            clear(containerId);
        }
    }

    /**
     * 자격 파일을 쓰고 helper 를 걸어둔다.
     *
     * <p>토큰을 base64 로 감아 파일에만 쓰는 것은 원래 의도를 유지한다 — 명령줄에 평문 토큰이
     * 들어가면 {@code ps} 와 docker exec 로그에 남는다. base64 는 암호화가 아니라 <b>명령줄
     * 노출을 줄이는 것</b>이고, 파일 잔존 문제는 {@link #clear} 가 맡는다.</p>
     */
    private void write(String containerId, String username, String userToken) {
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String b64 = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('" + CREDENTIALS_FILE
                        + "', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
        dockerService.exec(containerId,
                "git config --global credential.helper 'store --file " + CREDENTIALS_FILE + "'");
    }

    /**
     * 자격을 지운다. 실패해도 던지지 않는다 — 여기서 던지면 git 작업의 실제 실패 원인을 덮는다.
     *
     * <p>helper 설정도 함께 푼다. 파일만 지우면 설정이 없는 파일을 가리킨 채 남고, 그 상태에서
     * 인증이 필요한 명령은 <b>프롬프트 없이 조용히 실패</b>한다 — 다음 사람이 원인을 찾기
     * 어려워진다. 둘을 함께 되돌려 "자격이 없다"는 상태를 명확히 만든다.</p>
     */
    private void clear(String containerId) {
        try {
            dockerService.exec(containerId,
                    "rm -f " + CREDENTIALS_FILE + " && git config --global --unset credential.helper");
        } catch (Exception e) {
            // 컨테이너가 이미 죽었으면 지울 것도 없다. 그 외의 실패는 남겨서 보이게 한다.
            log.warn("[ContainerGitCredentials] 자격 정리 실패 — 컨테이너에 파일이 남을 수 있습니다. containerId={}",
                    containerId, e);
        }
    }
}
