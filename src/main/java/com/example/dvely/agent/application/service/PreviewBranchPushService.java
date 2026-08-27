package com.example.dvely.agent.application.service;

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

        String remoteUrl = "https://github.com/" + repoFullName + ".git";
        boolean hasGit = "yes".equals(
                dockerService.exec(containerId, "[ -d /workspace/app/.git ] && echo yes || echo no").trim());

        if (!hasGit) {
            if (isNew) writeGitignore(containerId);
            execOrThrow(containerId, "cd /workspace/app && git init -b preview", "git init");
            execOrThrow(containerId, "cd /workspace/app && git remote add origin " + remoteUrl, "git remote add");
            // 원격에 이미 preview 가 있으면 그 커밋을 부모로 삼는다. 저장소를 연결할 때
            // preparePreviewBranch 가 기본 브랜치 HEAD 에서 preview 를 갈라두기 때문에, 갓 init 한
            // 로컬 히스토리를 그대로 올리면 두 히스토리에 공통 조상이 없어 push 가 거부된다.
            // --soft 라서 작업 트리와 인덱스는 건드리지 않고 HEAD 만 원격 끝으로 옮긴다.
            dockerService.exec(containerId,
                    "cd /workspace/app && "
                            + "(git fetch origin preview 2>/dev/null "
                            + "&& git reset --soft FETCH_HEAD) || true");
        } else {
            execOrThrow(containerId, "cd /workspace/app && git remote set-url origin " + remoteUrl, "git remote set-url");
            execOrThrow(containerId, "cd /workspace/app && git checkout -B preview", "git checkout -B preview");
        }

        execOrThrow(containerId, "cd /workspace/app && git add -A", "git add");
        // 변경이 없으면 git diff --cached --quiet 가 0 으로 끝나 커밋을 건너뛴다. 변경이 있으면
        // 1 을 주고 커밋이 돌며, 그 커밋이 실패하면 전체가 0 이 아니다 — 그대로 실패로 본다.
        execOrThrow(containerId,
                "cd /workspace/app && git diff --cached --quiet || git commit -m 'feat: apply Qeploy Agent task "
                        + taskId + "'", "git commit");
        execOrThrow(containerId, "cd /workspace/app && git push -u origin preview", "git push");
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

    private void writeGitignore(String containerId) {
        String content = "node_modules/\ndist/\nbuild/\nout/\n.env\n.env.local\n";
        String b64     = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/workspace/app/.gitignore', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
    }
}
