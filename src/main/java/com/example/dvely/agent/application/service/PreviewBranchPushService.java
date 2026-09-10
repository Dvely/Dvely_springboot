package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Pushes a Docker container's working tree to the {@code preview} branch of a GitHub repository.
 * Extracted from {@link DeployAgentService} (design D10, Track Z #56) so the result-approval gate
 * (which must push the CODE step's output to {@code preview} the moment it decides to hold a task
 * for RESULT approval — before the DEPLOY step ever runs) and the DEPLOY step's own push can share
 * one implementation instead of maintaining the git/credential/.gitignore sequence twice.
 * <p>
 * Behavior is unchanged from the code this was extracted from: git is installed on demand, a
 * short-lived credential file is written (never logged), and the push is commit-if-changed
 * (idempotent — re-running with no working-tree changes is a no-op push of the existing HEAD).
 */
@Service
@RequiredArgsConstructor
public class PreviewBranchPushService {

    private final DockerContainerService dockerService;

    /**
     * @param isNew 이 컨테이너에 재사용할 .git 이 없어 새로 init 해야 하면 true. 시작용 .gitignore 를
     *              쓴다. 원격 저장소를 방금 만들었는지와는 무관하다. false 면 이전 clone 으로 생긴
     *              .git 을 그대로 쓴다(CodeAgentService.prepareProjectInContainer).
     */
    public void push(String containerId,
                     String userToken,
                     String username,
                     String repoFullName,
                     boolean isNew,
                     String taskId) {
        // apk 는 이미 git 이 있거나 이미지가 alpine 이 아닐 수 있어 실패를 허용한다. 정말 git 이
        // 없으면 아래 strict 명령들이 대신 드러낸다.
        dockerService.exec(containerId, "apk add --no-cache git");
        writeGitCredentials(containerId, username, userToken);
        dockerService.exec(containerId, "git config --global credential.helper 'store --file /tmp/.git-credentials'");
        dockerService.exec(containerId, "git config --global user.email 'agent@qeploy.com'");
        dockerService.exec(containerId, "git config --global user.name 'Qeploy Agent'");

        requireAppDir(containerId);

        String remoteUrl = "https://github.com/" + repoFullName + ".git";
        boolean hasGit = "yes".equals(
                dockerService.exec(containerId, "[ -d " + ContainerPaths.APP_DIR + "/.git ] && echo yes || echo no").trim());

        if (!hasGit) {
            if (isNew) writeGitignore(containerId);
            execOrThrow(containerId, ContainerPaths.inApp("git init -b preview"), "git init");
            execOrThrow(containerId, ContainerPaths.inApp("git remote add origin " + remoteUrl), "git remote add");
            // 원격에 이미 preview 가 있으면 그 커밋을 부모로 삼는다. 저장소를 연결할 때
            // preparePreviewBranch 가 기본 브랜치 HEAD 에서 preview 를 갈라두기 때문에, 갓 init 한
            // 로컬 히스토리를 그대로 올리면 두 히스토리에 공통 조상이 없어 push 가 거부된다.
            // --soft 라서 작업 트리와 인덱스는 건드리지 않고 HEAD 만 원격 끝으로 옮긴다.
            dockerService.exec(containerId,
                    ContainerPaths.inApp("(git fetch origin preview 2>/dev/null "
                            + "&& git reset --soft FETCH_HEAD) || true"));
        } else {
            execOrThrow(containerId, ContainerPaths.inApp("git remote set-url origin " + remoteUrl), "git remote set-url");
            execOrThrow(containerId, ContainerPaths.inApp("git checkout -B preview"), "git checkout -B preview");
        }

        execOrThrow(containerId, ContainerPaths.inApp("git add -A"), "git add");
        // 변경이 없으면 git diff --cached --quiet 가 0 으로 끝나 커밋을 건너뛴다. 변경이 있으면
        // 1 을 주고 커밋이 돌며, 그 커밋이 실패하면 전체가 0 이 아니다 — 그대로 실패로 본다.
        execOrThrow(containerId,
                ContainerPaths.inApp("git diff --cached --quiet || git commit -m 'feat: apply Qeploy Agent task "
                        + taskId + "'"), "git commit");
        execOrThrow(containerId, ContainerPaths.inApp("git push -u origin preview"), "git push");
    }

    /**
     * 실패하면 던진다.
     *
     * push 가 실패해도 조용히 넘어가던 것이 이 메서드가 생긴 이유다. DockerContainerService#exec
     * 은 종료 코드를 읽지 않아 인증 실패든 보호 브랜치든 그냥 문자열이 돌아왔고, 호출자는
     * 성공으로 알고 다음으로 갔다. 그 결과 감사 로그에는 PREVIEW_BRANCH_PUSHED 가 성공으로 남고,
     * 사용자에게는 "작업물을 preview 브랜치에 올렸습니다 — 프리뷰가 만료돼도 코드는 남습니다"가
     * 표시된다. 실제로는 아무것도 올라가지 않았고, 컨테이너가 만료되면 작업물은 사라진다.
     *
     * 예외 메시지에는 명령 전문을 넣지 않는다. 이 클래스는 자격 증명을 다루고, 그 명령줄이
     * 로그나 사용자 화면으로 흘러가면 안 된다 — 어떤 단계였는지와 git 이 남긴 출력만 남긴다.
     */
    private void execOrThrow(String containerId, String command, String step) {
        DockerContainerService.ExecResult result = dockerService.execWithExitCode(containerId, command);
        if (result.succeeded()) {
            return;
        }
        String output = result.output() == null ? "" : result.output().trim();
        String tail = output.length() > 500 ? output.substring(output.length() - 500) : output;
        throw new IllegalStateException(
                "preview 브랜치에 올리지 못했습니다(" + step + ", exitCode=" + result.exitCode() + "): " + tail);
    }

    private void writeGitCredentials(String containerId, String username, String userToken) {
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String b64  = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/tmp/.git-credentials', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
    }

    /**
     * /workspace/app 이 없으면 여기서 끝낸다. 없으면 첫 {@code cd} 가
     * {@code sh: cd: can't cd to /workspace/app} 로 죽는데, 그 문구는 "git init 이 실패했다"로
     * 보고돼 원인이 코드 에이전트가 파일을 엉뚱한 곳에 썼다는 사실을 가린다.
     *
     * <p>실제로 그렇게 한 번 막혔다(2026-09-07 dev, project 45): 프레임워크 없는 vanilla 요청이라
     * 스캐폴더가 돌지 않았고 — {@code app} 디렉터리를 만들어 주는 것이 스캐폴더뿐이었다 —
     * 코드 에이전트가 {@code /workspace} 루트에 index.html 을 썼다. 프리뷰는 index.html 을 찾아
     * 다니는 폴백이 있어 멀쩡히 떴고, 그래서 push 단계에 와서야 드러났다.</p>
     *
     * <p>여기서 {@code /workspace} 로 폴백하지 않는 이유: 무엇을 올릴지 짐작해서 올리는 것보다
     * 멈추는 편이 낫다. 사용자의 저장소에 잘못된 트리가 올라가면 되돌리기가 훨씬 비싸다.</p>
     */
    private void requireAppDir(String containerId) {
        String exists = dockerService.exec(
                containerId, "[ -d " + ContainerPaths.APP_DIR + " ] && echo yes || echo no").trim();
        if (!"yes".equals(exists)) {
            String found = dockerService.exec(
                    containerId, "ls -A /workspace 2>/dev/null | head -20").trim();
            throw new IllegalStateException(
                    "작업물이 " + ContainerPaths.APP_DIR + " 에 없어 저장소에 올리지 못했습니다. "
                            + "코드 에이전트가 다른 경로에 파일을 만든 것으로 보입니다. "
                            + "/workspace 내용: " + (found.isEmpty() ? "(비어 있음)" : found));
        }
    }

    private void writeGitignore(String containerId) {
        String content = "node_modules/\ndist/\nbuild/\nout/\n.env\n.env.local\n";
        String b64     = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('" + ContainerPaths.APP_DIR + "/.gitignore', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
    }
}
