package com.example.dvely.agent.infrastructure.git;

import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.ChangedPath;
import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.Snapshot;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 작업 트리를 서버에서 검토·반영한다(#523). 호출부는 PR-2 에서 붙는다.
 *
 * <ul>
 *   <li>{@link #inspect} — 스냅샷 트리·바뀐 경로·승인이 필요한 보호 경로·diff 를 만든다. push 하지 않는다.</li>
 *   <li>{@link #publish} — 스냅샷 커밋 1개를 대상 브랜치로 fast-forward push 한다. 그 사이 원격이 움직였으면
 *       base 를 다시 받아 한 번 더 시도한다. 기대 트리를 주면 그것과 같을 때만 push 한다(승인 뒤 반영).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerGitPublisher {

    static final PersonIdent AGENT = new PersonIdent("Qeploy Agent", "agent@qeploy.com");

    private final ServerGitProperties properties;
    private final ProtectedPathPolicy protectedPathPolicy;

    private final ConcurrentHashMap<String, ReentrantLock> repositoryLocks = new ConcurrentHashMap<>();

    public Inspection inspect(Request request, Set<ObjectId> serverGenerated) {
        try (ServerGitWorkspace workspace = open()) {
            RevCommit base = request.remoteUrl() == null ? null : fetchBase(workspace, request);
            Snapshot snapshot = snapshot(workspace, request, base);
            List<ChangedPath> changes = workspace.changedPaths(base, snapshot.tree());
            return new Inspection(
                    snapshot.tree(),
                    changes.stream().map(ChangedPath::path).distinct().toList(),
                    protectedPathPolicy.requiringApproval(changes, serverGenerated),
                    workspace.diff(base, snapshot.tree()));
        }
    }

    /**
     * @param expectedTree    승인한 트리. 있으면 다시 뜬 스냅샷이 그것과 같을 때만 push 한다
     * @param allowProtected  보호 경로 변경을 그대로 push 해도 되는지. false 면 승인이 필요한 경로가 있을 때
     *                        push 하지 않고 {@link ProtectedChangeRequiresApprovalException} 을 던진다
     */
    public Published publish(Request request, Optional<ObjectId> expectedTree, boolean allowProtected,
                             Set<ObjectId> serverGenerated) {
        // 같은 저장소로 가는 push 는 한 번에 하나만 — 서로의 base 를 밀어내며 재시도를 반복하지 않게 한다.
        ReentrantLock lock = repositoryLocks.computeIfAbsent(request.remoteUrl(), url -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return publishOnce(request, expectedTree, allowProtected, serverGenerated);
            } catch (NonFastForwardException first) {
                log.info("[ServerGit] 원격 {} 이 그 사이 바뀌어 base 를 다시 받아 재시도합니다", request.targetBranch());
                return publishOnce(request, expectedTree, allowProtected, serverGenerated);
            }
        } finally {
            lock.unlock();
        }
    }

    private Published publishOnce(Request request, Optional<ObjectId> expectedTree, boolean allowProtected,
                                  Set<ObjectId> serverGenerated) {
        try (ServerGitWorkspace workspace = open()) {
            RevCommit base = fetchBase(workspace, request);
            Snapshot snapshot = snapshot(workspace, request, base);
            if (expectedTree.isPresent() && !expectedTree.get().equals(snapshot.tree())) {
                throw new SnapshotChangedException();
            }
            if (!allowProtected) {
                List<String> protectedPaths = protectedPathPolicy.requiringApproval(
                        workspace.changedPaths(base, snapshot.tree()), serverGenerated);
                if (!protectedPaths.isEmpty()) {
                    throw new ProtectedChangeRequiresApprovalException(protectedPaths);
                }
            }
            Optional<ObjectId> commit = workspace.commit(base, snapshot.tree(), request.commitMessage(), AGENT);
            commit.ifPresent(id -> workspace.push(request.remoteUrl(), request.credentials(), id, request.targetBranch()));
            return new Published(commit, snapshot.tree());
        }
    }

    private ServerGitWorkspace open() {
        return ServerGitWorkspace.create(Path.of(properties.workDir()), properties);
    }

    private static RevCommit fetchBase(ServerGitWorkspace workspace, Request request) {
        for (String branch : request.baseBranches()) {
            Optional<RevCommit> base = workspace.fetchBase(request.remoteUrl(), request.credentials(), branch);
            if (base.isPresent()) {
                return base.get();
            }
        }
        // 후보가 하나도 없으면 원격의 기본 브랜치(HEAD 가 가리키는 것)를 쓴다 — main 이 아닌 저장소도 있다.
        Optional<String> defaultBranch = workspace.defaultBranch(request.remoteUrl(), request.credentials());
        if (defaultBranch.isPresent() && !request.baseBranches().contains(defaultBranch.get())) {
            return workspace.fetchBase(request.remoteUrl(), request.credentials(), defaultBranch.get()).orElse(null);
        }
        return null;   // 빈 저장소 — 첫 커밋이 된다
    }

    private static Snapshot snapshot(ServerGitWorkspace workspace, Request request, RevCommit base) {
        return workspace.snapshotTree(request.archive(), base);
    }

    /**
     * @param remoteUrl     {@code https://github.com/<owner>/<repo>.git}
     * @param credentials   서버에서만 쓰는 GitHub 자격
     * @param targetBranch  push 할 브랜치(보통 {@code preview})
     * @param baseBranches  base 로 쓸 브랜치 후보. 앞에서부터 처음 있는 것을 쓴다(예: preview, main)
     * @param archive       컨테이너 작업 디렉터리 tar 를 여는 함수. 스냅샷 한 번에 두 번, 재시도 때 다시 부른다
     * @param commitMessage 스냅샷 커밋 메시지
     */
    public record Request(String remoteUrl,
                          CredentialsProvider credentials,
                          String targetBranch,
                          List<String> baseBranches,
                          Supplier<InputStream> archive,
                          String commitMessage) {
    }

    public record Inspection(ObjectId tree, List<String> changedPaths, List<String> requiringApproval, String diff) {
    }

    public record Published(Optional<ObjectId> commit, ObjectId tree) {
    }

    /** 승인 없이 반영하면 안 되는 경로가 바뀌었다. 메시지는 사용자에게 그대로 보인다. */
    public static class ProtectedChangeRequiresApprovalException extends ServerGitException {
        private final List<String> paths;

        public ProtectedChangeRequiresApprovalException(List<String> paths) {
            super("작업 결과에 .github 아래 변경(" + String.join(", ", paths.subList(0, Math.min(3, paths.size())))
                    + (paths.size() > 3 ? " 외 " + (paths.size() - 3) + "개" : "")
                    + ")이 있어 결과 승인 없이 저장소에 반영하지 않았습니다. 결과 승인을 거쳐 반영해 주세요.", null);
            this.paths = List.copyOf(paths);
        }

        public List<String> paths() {
            return paths;
        }
    }

    /** 승인 뒤 다시 뜬 스냅샷이 승인한 것과 다르다. 다시 검토를 받아야 한다. */
    public static class SnapshotChangedException extends ServerGitException {
        public SnapshotChangedException() {
            super("승인한 뒤 작업 내용이 바뀌었습니다. 바뀐 내용을 다시 확인해 주세요.", null);
        }
    }
}
