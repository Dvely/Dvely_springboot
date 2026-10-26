package com.example.dvely.agent.infrastructure.docker;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 안 git 인증을 <b>파일 없이</b> 공급한다 (#413).
 *
 * <h2>무엇이 문제였나</h2>
 * 세 곳(프리뷰 워크스페이스 clone, preview 브랜치 push, 백엔드 배포 clone)이 각자
 * {@code /tmp/.git-credentials} 에 <b>사용자의 GitHub OAuth 액세스 토큰을 평문으로</b> 쓰고
 * 아무도 지우지 않았다. 컨테이너 수명(TTL 기본 30분, 접근마다 연장, 승인 hold 로 최대 6시간)
 * 내내 남았고, 그 컨테이너는 {@code npm install} 로 받은 의존성과 에이전트가 쓴 앱 코드를 돈다.
 * 파일을 쓰는 uid 와 앱을 돌리는 uid 가 같아서({@code node}) 그 코드가 읽을 수 있었다.
 *
 * <p>1차로 자격 수명을 git 작업 구간으로 좁혔다(PR #418). 이제 <b>파일 자체를 없앤다.</b></p>
 *
 * <h2>어떻게 파일 없이 공급하나</h2>
 * git 의 {@code credential.helper} 는 {@code !} 로 시작하면 셸 명령으로 실행된다. 그 명령이
 * 환경변수를 읽어 {@code username}/{@code password} 를 표준출력에 내면 git 이 그것을 쓴다.
 *
 * <pre>
 * git -c credential.helper='!f() { printf "username=%s\npassword=%s\n" "$U" "$T"; }; f' fetch ...
 * </pre>
 *
 * <p>토큰은 {@link DockerContainerService#execWithExitCode(String, String, List)} 의 env 로만
 * 간다 — 그 메서드가 env 를 명령 문자열이 아니라 {@code ExecCreateCmd#withEnv} 로 넘기므로,
 * {@code log.debug("Docker exec: {}", command)} 와 예외 메시지에는 토큰이 남지 않는다. 헬퍼
 * 문자열은 홑따옴표 안이라 바깥 셸이 {@code $U}/{@code $T} 를 건드리지 않고, git 이 띄우는
 * 안쪽 셸에서만 펼쳐진다.</p>
 *
 * <h2>실측으로 확인했다</h2>
 * 토큰 없이도 확인할 수 있는 지점이 있다 — {@code git credential fill} 은 헬퍼를 불러 해석된
 * 자격을 찍는다. {@code node:20-alpine} + git 2.52 컨테이너에서 env 로만 토큰을 넘겨
 * {@code username}/{@code password} 가 그대로 나오는 것을 봤다(전역 helper 가 없는 상태에서).
 * 인용·env 전파·git 의 {@code !} 셸 호출이 이 변경의 유일한 위험이었고 그것을 없앴다.
 *
 * <h2>남는 것</h2>
 * 토큰이 도는 동안 git 프로세스의 {@code /proc/<pid>/environ} 은 같은 uid 가 읽을 수 있다.
 * 파일과 달리 <b>그 명령이 끝나면 사라진다</b>. 더 좁히려면 사용자 토큰을 installation
 * 토큰으로 바꿔야 한다(#414 와 함께 볼 것).
 */
@Component
@RequiredArgsConstructor
public class ContainerGitCredentials {

    /** 헬퍼가 읽을 env 이름. 값은 명령 문자열이 아니라 exec 의 env 로만 전달된다. */
    private static final String USER_VAR = "QEPLOY_GIT_USER";
    private static final String TOKEN_VAR = "QEPLOY_GIT_TOKEN";

    /**
     * git 이 셸로 실행하는 인라인 credential helper.
     *
     * <p>홑따옴표를 쓰지 않는다 — 이 문자열 전체가 바깥 명령의 홑따옴표 안에 들어가므로, 안에
     * 홑따옴표가 있으면 인용이 깨진다. {@code printf} 의 {@code \n} 은 두 글자로 들어가
     * printf 가 해석한다.</p>
     */
    private static final String INLINE_HELPER =
            "!f() { test \"$1\" = get || return 0; p=; h=; "
                    + "while read -r l; do case \"$l\" in protocol=https) p=1;; host=github.com) h=1;; esac; done; "
                    + "test \"$p$h\" = 11 || return 0; "
                    + "printf \"username=%s\\npassword=%s\\n\" \"$" + USER_VAR + "\" \"$" + TOKEN_VAR + "\"; }; f";

    /**
     * 인증 git 호출이 따르면 안 되는 설정 키. 요청 대상·자격 공급을 바꾸거나 git 이 외부 명령을
     * 실행하게 만드는 것들이다. 시스템·전역·저장소 설정 어디에 있든(include 포함) 하나라도 있으면
     * 그 호출을 돌리지 않는다. 컨테이너 안 설정은 같은 컨테이너에서 도는 코드가 바꿀 수 있다.
     */
    static final String UNSAFE_CONFIG_PATTERN = "^("
            + "url\\.|credential\\.(.+\\.)?helper$|https?\\.|include\\.|includeif\\.|filter\\.|protocol\\."
            + "|uploadpack\\.|receivepack\\.|gpg\\."
            + "|core\\.(hookspath|fsmonitor|sshcommand|askpass|gitproxy|alternaterefscommand)$"
            + "|remote\\..*\\.(pushurl|proxy|receivepack|uploadpack|vcs)$"
            + "|diff\\..*\\.(command|textconv)$|merge\\..*\\.driver$"
            + ")";

    /**
     * 인증 git 호출은 PATH 가 아니라 이 경로의 git 을 쓴다. 호출 직전 이 파일과 git 의 exec-path
     * (git-remote-https 등 하위 프로그램이 있는 곳)가 root 소유이고 그룹·기타가 쓸 수 없는지 같은 셸에서
     * 확인한다 — 같은 컨테이너의 다른 uid 가 바꿔 끼울 수 없는 실행 파일만 토큰을 받는다.
     */
    static final String GIT_BIN = "/usr/bin/git";

    /** 위 키가 있을 때 인증 git 호출 대신 내는 종료 코드. 호출부 오류 메시지에서 알아볼 수 있게 고정한다. */
    static final int UNSAFE_CONFIG_EXIT = 97;

    /**
     * 인증 git 호출 앞에 붙는 검사. 같은 셸 안에서 돌아 그 사이에 설정이 바뀔 틈이 없다.
     * 설정 읽기 자체가 실패하면(git 없음 등) 뒤 git 호출도 같은 이유로 실패하므로 막지 않는다.
     */
    private static final String UNSAFE_CONFIG_GUARD =
            "{ [ \"$(find " + GIT_BIN + " \"$(" + GIT_BIN + " --exec-path 2>/dev/null)\" -maxdepth 0"
                    + " -user 0 ! -perm -020 ! -perm -002 2>/dev/null | wc -l | tr -d ' ')\" = 2 ]"
                    + " || { echo 'qeploy: git 실행 파일을 신뢰할 수 없어 인증 git 호출을 하지 않습니다' >&2;"
                    + " exit " + UNSAFE_CONFIG_EXIT + "; };"
                    + " ! " + GIT_BIN + " config --get-regexp '" + UNSAFE_CONFIG_PATTERN + "' >/dev/null 2>&1"
                    + " || { echo 'qeploy: 컨테이너 git 설정이 허용 범위를 벗어나 인증 git 호출을 하지 않습니다' >&2;"
                    + " exit " + UNSAFE_CONFIG_EXIT + "; }; } && ";

    private static final Pattern BARE_GIT = Pattern.compile("(^|[\\s;&|(])git ");

    private static final Pattern TRAILING_ASSIGNMENT = Pattern.compile("(?:[A-Z_][A-Z0-9_]*=\\S*\\s+)+$");

    private final DockerContainerService dockerService;

    /**
     * git 명령에 자격을 붙인다. 인증이 필요한 명령(clone·fetch·push)에만 쓴다.
     *
     * <p>{@code git} 으로 시작하는 명령의 그 자리에 {@code -c credential.helper=...} 를 끼운다.
     * 자격이 필요 없는 명령({@code init}·{@code add}·{@code commit})에는 붙이지 않는다 — 붙여도
     * 무해하지만, 붙은 자리가 곧 "여기서 토큰이 필요하다"는 표시여야 읽는 사람이 범위를 안다.</p>
     *
     * <p><b>명령 형태가 바뀐다.</b> {@code git clone ...} 이 {@code git -c credential.helper=... clone ...}
     * 이 되므로 {@code "git clone"} 같은 부분문자열이 더는 연속하지 않는다. 명령을 문자열로
     * 매칭하는 코드·테스트가 있으면 서브커맨드({@code " clone "}, {@code "push -u origin"})로
     * 맞춰야 한다 — 이 변경에서 테스트 다섯 곳이 그렇게 걸렸다.</p>
     *
     * @param gitCommand {@code git} 으로 시작하거나 {@code cd X && git ...} 형태의 명령
     */
    public String authed(String gitCommand) {
        int at = gitCommand.indexOf("git ");
        if (at < 0) {
            throw new IllegalArgumentException("git 명령이 아닙니다: " + gitCommand);
        }
        // GIT_TERMINAL_PROMPT=0 처럼 git 앞에 붙은 환경 지정은 git 에 걸린 것이라 그 앞에 검사를 둔다.
        int start = at;
        Matcher assignment = TRAILING_ASSIGNMENT.matcher(gitCommand.substring(0, at));
        if (assignment.find()) {
            start = assignment.start();
        }
        // hooks 는 끄고, 저장소·전역 설정의 helper 는 비운 뒤 인라인 helper 하나만 둔다.
        // 같은 exec 의 뒤따르는 git 호출(예: fetch 뒤 reset)도 토큰이 든 env 안에서 돈다 — 모두 절대 경로로 바꾼다.
        String rest = BARE_GIT.matcher(gitCommand.substring(at + "git ".length())).replaceAll("$1" + GIT_BIN + " ");
        return gitCommand.substring(0, start)
                + UNSAFE_CONFIG_GUARD
                + gitCommand.substring(start, at)
                + GIT_BIN + " -c core.hooksPath=/dev/null -c credential.helper= -c credential.helper='" + INLINE_HELPER + "' "
                + rest;
    }

    /**
     * 토큰을 담은 exec env. {@link #authed} 로 감싼 명령과 반드시 함께 넘긴다.
     *
     * <p>둘 중 하나만 쓰면 조용히 실패한다 — env 없이 헬퍼만 있으면 빈 자격을 내고, 헬퍼 없이
     * env 만 있으면 git 이 그 값을 볼 이유가 없다. 그래서 호출부가 둘을 같이 쓰는지
     * {@code ContainerGitCredentialsTest} 가 지킨다.</p>
     *
     * <p><b>이 값은 로그 설정 한 줄에 의존해 보호된다.</b> docker-java 3.7.1 은 명령 객체의 모든
     * 필드를 DEBUG 로 reflection 덤프하므로, env 에 실린 토큰도 그 대상이다. {@code
     * application.yaml} 이 {@code com.github.dockerjava.core.command} 를 WARN 으로 못박아
     * 막는다 — 그 줄을 풀면 토큰이 평문으로 로그에 남는다. 우리 쪽 로거는 안전하다({@code
     * DockerContainerService} 는 command 만 찍고 env 는 찍지 않는다).</p>
     *
     * <p>같은 위험을 {@code CodingAgentContainerRunner} 는 반대로 막는다 — env 를 아예 쓰지 않고
     * 파일 스테이징으로 넘긴다. 여기서 env 를 고른 것은 프리뷰 컨테이너가 사용자 코드를
     * 실행하기 때문이다({@code npm install} 의 postinstall 포함): 컨테이너 안에 남는 자격 파일은
     * 그 코드가 읽을 수 있고, env 는 읽을 수 없다. 그 트레이드오프가 #413 의 결론이다.</p>
     */
    public List<String> env(String username, String userToken) {
        return List.of(USER_VAR + "=" + username, TOKEN_VAR + "=" + userToken);
    }

    /**
     * 자격이 필요한 git 명령을 돌린다. 성공 여부는 호출부가 판단한다.
     *
     * <p>이 메서드를 쓰면 {@link #authed}·{@link #env} 를 따로 조합할 일이 없다 — 한쪽만 쓰는
     * 실수를 구조적으로 막는다.</p>
     */
    public DockerContainerService.ExecResult exec(String containerId,
                                                  String username,
                                                  String userToken,
                                                  String gitCommand) {
        return dockerService.execWithExitCode(containerId, authed(gitCommand), env(username, userToken));
    }
}
