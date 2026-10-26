package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 에이전트 결과물이 GitHub Actions 를 건드리는지 본다(#508).
 *
 * <p>승인 정책은 계획 스텝 <b>타입</b> 단위라, 프로젝트가 CHANGE·RESULT 승인을 끄면 결과물에
 * 무엇이 들었든 그대로 반영된다. 그런데 workflow 는 저장소의 Actions secrets(사용자 AWS 키 등)를
 * 읽을 수 있는 코드다. 저장소·템플릿·웹 내용에 심긴 프롬프트 인젝션이 workflow 하나를 끼워 넣으면
 * 사람이 보지 않은 채 비밀이 빠져나간다. 그래서 이 경로가 들어 있으면 정책과 무관하게 결과 승인을
 * 받는다.</p>
 *
 * <p>보는 범위는 workflow 와 그것이 부르는 로컬 액션({@code .github/actions/**}), 그리고
 * {@code .github} 자체다. {@code .github} 를 심볼릭 링크나 파일로 바꿔치는 것도 같은 효과를 낸다.</p>
 *
 * <p>한계: 컨테이너 안의 git 메타데이터를 기준으로 본다. 셸을 가진 에이전트가 {@code .git} 자체를
 * 조작하면 속일 수 있다 — 그 수준의 방어는 push 된 커밋을 GitHub compare API 로 보는 쪽의 몫이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowPathGuard {

    private static final List<String> GUARDED_PREFIXES = List.of(".github/workflows", ".github/actions");

    private final DockerContainerService dockerService;

    /**
     * 컨테이너 작업물이 기본 브랜치 대비 보호 경로를 건드리는지. 커밋된 변경·작업 트리 변경·새 파일을
     * 모두 본다. 확인 자체가 실패하면 true — 못 봤다는 이유로 승인을 건너뛰지 않는다.
     */
    public boolean touchesGuardedPath(String containerId) {
        dockerService.installPackages(containerId, "git");
        // --no-renames: 이름 변경을 삭제+추가로 펼쳐 옛 경로와 새 경로가 모두 나오게 한다. 다른 이름으로
        // 옮겨 들이거나 workflow 를 밖으로 빼는 것을 둘 다 잡는다.
        // origin/HEAD 가 없으면(기본 브랜치 정보 없는 clone) HEAD 기준으로 본다.
        DockerContainerService.ExecResult result = dockerService.execWithExitCode(containerId,
                ContainerPaths.inApp("(git add -A -N . >/dev/null 2>&1 || true) && "
                        + "base=$(git rev-parse --verify -q origin/HEAD || git rev-parse --verify -q HEAD) && "
                        + "git -c core.quotepath=false diff --no-ext-diff --no-renames --name-only -z \"$base\" -- ."));
        if (!result.succeeded()) {
            log.warn("[WorkflowPathGuard] 변경 경로를 확인하지 못해 결과 승인을 요구합니다. exitCode={}",
                    result.exitCode());
            return true;
        }
        return touchesGuardedPath(List.of(result.output().split("\0")));
    }

    /** 경로 목록 중 하나라도 보호 경로면 true. 대소문자·{@code ./} 접두사·역슬래시를 정규화해 비교한다. */
    static boolean touchesGuardedPath(Collection<String> paths) {
        return paths.stream().map(WorkflowPathGuard::normalize).anyMatch(WorkflowPathGuard::isGuarded);
    }

    private static boolean isGuarded(String path) {
        if (path.isEmpty()) {
            return false;
        }
        if (path.equals(".github")) {
            return true;
        }
        return GUARDED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String normalize(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.substring(path.startsWith("./") ? 2 : 1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // GitHub 은 대소문자를 구분하지만, 대소문자만 다른 경로로 검사를 비켜 가게 두지 않는다.
        return path.toLowerCase(Locale.ROOT);
    }
}
