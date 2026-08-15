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
            dockerService.exec(containerId, "cd /workspace/app && git init -b preview");
            dockerService.exec(containerId, "cd /workspace/app && git remote add origin " + remoteUrl);
            // 원격에 이미 preview 가 있으면 그 커밋을 부모로 삼는다. 저장소를 연결할 때
            // preparePreviewBranch 가 기본 브랜치 HEAD 에서 preview 를 갈라두기 때문에, 갓 init 한
            // 로컬 히스토리를 그대로 올리면 두 히스토리에 공통 조상이 없어 push 가 거부된다.
            // --soft 라서 작업 트리와 인덱스는 건드리지 않고 HEAD 만 원격 끝으로 옮긴다.
            dockerService.exec(containerId,
                    "cd /workspace/app && "
                            + "(git fetch origin preview 2>/dev/null "
                            + "&& git reset --soft FETCH_HEAD) || true");
        } else {
            dockerService.exec(containerId, "cd /workspace/app && git remote set-url origin " + remoteUrl);
            dockerService.exec(containerId, "cd /workspace/app && git checkout -B preview");
        }

        dockerService.exec(containerId, "cd /workspace/app && git add -A");
        dockerService.exec(containerId,
                "cd /workspace/app && git diff --cached --quiet || git commit -m 'feat: apply Qeploy Agent task "
                        + taskId + "'");
        dockerService.exec(containerId, "cd /workspace/app && git push -u origin preview");
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
