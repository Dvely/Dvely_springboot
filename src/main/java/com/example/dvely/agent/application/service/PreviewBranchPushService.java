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
     * @param isNew true for a repository this container has never pushed to before (fresh
     *              {@code git init}) — writes a starter {@code .gitignore} and initializes the
     *              local repo with {@code preview} as its default branch. False reuses the
     *              container's existing {@code .git} (already on some branch from a prior clone
     *              — see {@code CodeAgentService#prepareProjectInContainer}) and just retargets
     *              the remote and checks out {@code preview}.
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
