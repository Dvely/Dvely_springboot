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

    /**
     * 커밋 작성자. <b>커밋할 때마다 새로 만든다</b> — {@code PersonIdent} 는 만든 시각을 커밋 시각으로 담는다.
     * 예전에는 static 상수 하나를 써서 모든 커밋에 클래스가 처음 로드된 시각이 찍혔다(dev 에서 약 5시간
     * 과거로 보였다).
     */
    static PersonIdent agent() {
        return new PersonIdent("Qeploy Agent", "agent@qeploy.com");
    }

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
        return publish(request, expectedTree, allowProtected, serverGenerated, ExpectedBase.UNKNOWN);
    }

    /**
     * @param expectedBase 작업 트리를 준비할 때의 base. 받은 base 가 이것과 다르면 push 하지 않는다 — 스냅샷은 트리를
     *                     통째로 갈아끼우므로, 그 사이 원격에 생긴 변경을 새 커밋이 조용히 되돌린다. 원격이 움직여 다시
     *                     시도할 때도 같은 검사를 받는다
     * @throws RemoteMovedException 원격 base 가 준비 시점과 다르다
     */
    public Published publish(Request request, Optional<ObjectId> expectedTree, boolean allowProtected,
                             Set<ObjectId> serverGenerated, ExpectedBase expectedBase) {
        // 같은 저장소로 가는 push 는 한 번에 하나만 — 서로의 base 를 밀어내며 재시도를 반복하지 않게 한다.
        ReentrantLock lock = repositoryLocks.computeIfAbsent(request.remoteUrl(), url -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return publishOnce(request, expectedTree, allowProtected, serverGenerated, expectedBase);
            } catch (NonFastForwardException first) {
                log.info("[ServerGit] 원격 {} 이 그 사이 바뀌어 base 를 다시 받아 재시도합니다", request.targetBranch());
                return publishOnce(request, expectedTree, allowProtected, serverGenerated, expectedBase);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@code targetBranch} 를 {@code sourceBranch} 와 같은 내용으로 되돌리는 커밋을 올린다(#532 거절 REVERT).
     * 강제 push 가 아니다. 커밋의 부모는 target 의 현재 head 와 source 의 head 둘이고 트리는 source 와 같다 —
     * 두 번째 부모 덕에 되돌린 뒤의 {@code source...target} 비교가 비어 보인다. 이미 같은 내용이면 아무것도
     * 올리지 않는다(재시도해도 같은 결과).
     */
    public Optional<ObjectId> resetTo(String remoteUrl, CredentialsProvider credentials, String targetBranch,
                                      String sourceBranch, String commitMessage) {
        ReentrantLock lock = repositoryLocks.computeIfAbsent(remoteUrl, url -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return resetOnce(remoteUrl, credentials, targetBranch, sourceBranch, commitMessage);
            } catch (NonFastForwardException first) {
                log.info("[ServerGit] 원격 {} 이 그 사이 바뀌어 다시 받아 되돌립니다", targetBranch);
                return resetOnce(remoteUrl, credentials, targetBranch, sourceBranch, commitMessage);
            }
        } finally {
            lock.unlock();
        }
    }

    private Optional<ObjectId> resetOnce(String remoteUrl, CredentialsProvider credentials, String targetBranch,
                                         String sourceBranch, String commitMessage) {
        try (ServerGitWorkspace workspace = open()) {
            Optional<RevCommit> target = workspace.fetchBase(remoteUrl, credentials, targetBranch);
            if (target.isEmpty()) {
                return Optional.empty();   // 되돌릴 브랜치가 없다
            }
            RevCommit source = workspace.fetchBase(remoteUrl, credentials, sourceBranch)
                    .orElseThrow(() -> new ServerGitException("기준 브랜치가 저장소에 없습니다: " + sourceBranch, null));
            Optional<ObjectId> commit = workspace.mergeCommit(
                    target.get(), source, source.getTree().getId(), commitMessage, agent());
            commit.ifPresent(id -> workspace.push(remoteUrl, credentials, id, targetBranch));
            return commit;
        }
    }

    private Published publishOnce(Request request, Optional<ObjectId> expectedTree, boolean allowProtected,
                                  Set<ObjectId> serverGenerated, ExpectedBase expectedBase) {
        try (ServerGitWorkspace workspace = open()) {
            RevCommit base = fetchBase(workspace, request);
            if (!expectedBase.matches(base)) {
                throw new RemoteMovedException(request.targetBranch());
            }
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
            Optional<ObjectId> commit = workspace.commit(base, snapshot.tree(), request.commitMessage(), agent());
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

    /**
     * 작업 트리를 준비한 시점의 base. {@link #UNKNOWN} 이면 검사하지 않는다(기록이 없는 컨테이너 — 이 장치 이전에 준비됐다).
     *
     * @param commit null 이면 빈 저장소에서 준비했다는 뜻이다(known 일 때)
     */
    public record ExpectedBase(boolean known, ObjectId commit) {

        public static final ExpectedBase UNKNOWN = new ExpectedBase(false, null);

        public static ExpectedBase emptyRepository() {
            return new ExpectedBase(true, null);
        }

        public static ExpectedBase of(ObjectId commit) {
            return new ExpectedBase(true, commit);
        }

        boolean matches(RevCommit base) {
            if (!known) {
                return true;
            }
            return commit == null ? base == null : base != null && commit.equals(base.getId());
        }
    }

    /** 작업 트리를 준비한 뒤 원격이 바뀌었다. 덮어쓰면 그 변경이 되돌려지므로 반영하지 않는다. 메시지는 사용자에게 그대로 보인다. */
    public static class RemoteMovedException extends ServerGitException {
        public RemoteMovedException(String branch) {
            super("작업하는 동안 저장소의 " + branch + " 브랜치가 바뀌어(직접 수정했거나 다른 작업이 먼저 반영됨), 그 변경을 "
                    + "덮어쓰지 않으려고 반영을 멈췄습니다. 작업을 다시 요청하면 최신 상태에서 시작합니다.", null);
        }
    }

    /** 승인 없이 반영하면 안 되는 경로가 바뀌었다. 메시지는 사용자에게 그대로 보인다. */
    public static class ProtectedChangeRequiresApprovalException extends ServerGitException {
        private final List<String> paths;

        public ProtectedChangeRequiresApprovalException(List<String> paths) {
            this(paths, "결과 승인을 거쳐 반영해 주세요.");
        }

        /** @param guidance 상황에 맞는 다음 행동 안내(경로 목록 뒤에 붙는다) */
        public ProtectedChangeRequiresApprovalException(List<String> paths, String guidance) {
            super("작업 결과에 .github 아래 변경(" + describe(paths) + ")이 있어 저장소에 올리지 않았습니다. " + guidance, null);
            this.paths = List.copyOf(paths);
        }

        private static String describe(List<String> paths) {
            return String.join(", ", paths.subList(0, Math.min(3, paths.size())))
                    + (paths.size() > 3 ? " 외 " + (paths.size() - 3) + "개" : "");
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
