package com.example.dvely.agent.infrastructure.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;
import org.apache.commons.io.input.BoundedInputStream;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * 컨테이너 작업 트리를 서버에서 커밋·push 하는 임시 git 저장소(#523, {@code docs/server-side-git-design.md}).
 *
 * <p>작업 하나에 하나를 만들고 {@link #close()} 로 지운다. 저장소는 bare 이고 작업 트리가 없다 — 컨테이너
 * 작업 트리는 tar 스트림에서 바로 트리 객체로 만들고 디스크에 풀지 않는다.</p>
 */
public final class ServerGitWorkspace implements AutoCloseable {

    private static final String BASE_REF_PREFIX = "refs/remotes/base/";
    private static final List<String> ALWAYS_EXCLUDED_SEGMENTS = List.of(".git", "node_modules");
    private static final long MAX_GITIGNORE_BYTES = 1024 * 1024;

    private final Path directory;
    private final Repository repository;
    private final ServerGitProperties limits;

    private ServerGitWorkspace(Path directory, Repository repository, ServerGitProperties limits) {
        this.directory = directory;
        this.repository = repository;
        this.limits = limits;
    }

    public static ServerGitWorkspace create(Path parent, ServerGitProperties limits) {
        try {
            Files.createDirectories(parent);
            Path directory = Files.createTempDirectory(parent, "qeploy-git-");
            Repository repository = FileRepositoryBuilder.create(directory.toFile());
            repository.create(true);
            // 작업 단위로 만들고 지우는 저장소다. 자동 gc 가 뒤에서 돌면 지우는 도중 파일이 생기고 사라진다.
            repository.getConfig().setInt("gc", null, "auto", 0);
            repository.getConfig().setBoolean("gc", null, "autoDetach", false);
            repository.getConfig().save();
            return new ServerGitWorkspace(directory, repository, limits);
        } catch (IOException e) {
            throw new UncheckedIOException("서버 git 작업 공간을 만들지 못했습니다", e);
        }
    }

    /** 원격 {@code branch} 끝 커밋을 얕게 받는다. 브랜치가 없으면 비어 있다. */
    public Optional<RevCommit> fetchBase(String remoteUrl, CredentialsProvider credentials, String branch) {
        String remoteRef = Constants.R_HEADS + branch;
        try (Git git = Git.wrap(repository)) {
            boolean exists = git.lsRemote().setRemote(remoteUrl).setCredentialsProvider(credentials).setHeads(true)
                    .call().stream().map(Ref::getName).anyMatch(remoteRef::equals);
            if (!exists) {
                return Optional.empty();
            }
            git.fetch().setRemote(remoteUrl).setCredentialsProvider(credentials).setDepth(1)
                    .setRefSpecs(new RefSpec("+" + remoteRef + ":" + BASE_REF_PREFIX + branch)).call();
            ObjectId id = repository.resolve(BASE_REF_PREFIX + branch);
            try (RevWalk walk = new RevWalk(repository)) {
                return Optional.of(walk.parseCommit(id));
            }
        } catch (GitAPIException | IOException e) {
            throw new ServerGitException("기준 브랜치를 받지 못했습니다: " + branch, e);
        }
    }

    /**
     * 컨테이너 디렉터리 tar(데몬이 만든 것, 첫 경로 구간이 디렉터리 이름)를 트리로 만든다.
     *
     * <p>포함 규칙은 {@code git add -A} 와 같다. base 에 있는 경로는 ignore 와 무관하게 작업 트리에 있으면
     * 갱신하고 없으면 삭제한다. base 에 없는 경로에만 {@code .git}·{@code node_modules} 제외와
     * {@code .gitignore} 를 적용한다 — 그래서 {@code .gitignore} 한 줄로 추적 중인 파일이 지워지지 않는다.</p>
     *
     * <p><b>tar 를 두 번 읽는다.</b> 첫 번째는 헤더만(경로·모드·크기·링크, {@code .gitignore} 본문) 읽어 무엇을
     * 넣을지 정한다. 이때 원시 상한(엔트리 수·바이트)을 넘으면 그 자리에서 멈춘다. 두 번째는 넣기로 정한
     * 파일만 본문을 읽어 저장한다 — ignore 로 빠질 빌드 산출물·캐시는 서버 저장소에 쌓이지 않는다.
     * 스트림 자체에도 읽기 상한을 건다.</p>
     *
     * @param archive tar 를 여는 함수. 두 번 부른다
     */
    public Snapshot snapshotTree(Supplier<InputStream> archive, RevCommit base) {
        Map<String, BaseEntry> basePaths = base == null ? Map.of() : readBasePaths(base);
        Plan plan = plan(archive, basePaths);
        Map<String, IgnoreNode> ignoreNodes = parseIgnores(plan.gitignores());

        TreeMap<String, Header> included = new TreeMap<>();
        for (Header h : plan.headers().values()) {
            if (h.tracked() || !isIgnored(h.path(), ignoreNodes)) {
                included.put(h.path(), h);
            }
        }
        // 하드링크는 tar 안에서 먼저 나온 정규 파일의 내용을 쓴다. 풀리지 않으면 추적 파일은 실패, 새 경로는 뺀다.
        Set<String> needContent = new HashSet<>();
        for (Header h : List.copyOf(included.values())) {
            if (h.kind() != Kind.HARDLINK) {
                continue;
            }
            Header target = plan.headers().get(h.linkTarget());
            boolean resolvable = target != null && target.kind() == Kind.FILE && target.order() < h.order();
            if (!resolvable) {
                if (h.tracked()) {
                    throw new ServerGitException("하드링크를 해석하지 못했습니다: " + h.path(), null);
                }
                included.remove(h.path());
                continue;
            }
            needContent.add(target.path());
        }
        long bytes = 0;
        for (Header h : included.values()) {
            bytes += h.kind() == Kind.HARDLINK ? plan.headers().get(h.linkTarget()).size() : h.size();
        }
        if (included.size() > limits.maxFiles() || bytes > limits.maxBytes()) {
            throw new SnapshotLimitExceededException(included.size(), limits.maxFiles(), bytes, limits.maxBytes());
        }
        for (Header h : included.values()) {
            if (h.kind() == Kind.FILE) {
                needContent.add(h.path());
            }
        }

        try (ObjectInserter inserter = repository.newObjectInserter()) {
            Map<String, ObjectId> blobs = readContents(archive, plan, needContent, inserter);
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            TreeMap<String, DirCacheEntry> sorted = new TreeMap<>();
            for (Header h : included.values()) {
                DirCacheEntry e = new DirCacheEntry(h.path());
                switch (h.kind()) {
                    case SYMLINK -> {
                        e.setFileMode(FileMode.SYMLINK);
                        e.setObjectId(inserter.insert(Constants.OBJ_BLOB, h.linkTarget().getBytes(StandardCharsets.UTF_8)));
                    }
                    case HARDLINK -> {
                        Header target = plan.headers().get(h.linkTarget());
                        e.setFileMode(target.mode());
                        e.setObjectId(blobs.get(target.path()));
                    }
                    default -> {
                        e.setFileMode(h.mode());
                        e.setObjectId(blobs.get(h.path()));
                    }
                }
                sorted.put(h.path(), e);
            }
            // 서브모듈은 스냅샷으로 만들 수 없다 — base 의 가리킴을 그대로 둔다.
            basePaths.forEach((path, baseEntry) -> {
                if (baseEntry.mode() == FileMode.GITLINK) {
                    DirCacheEntry e = new DirCacheEntry(path);
                    e.setFileMode(FileMode.GITLINK);
                    e.setObjectId(baseEntry.id());
                    sorted.put(path, e);
                }
            });
            sorted.values().forEach(builder::add);
            builder.finish();
            ObjectId tree = index.writeTree(inserter);
            inserter.flush();
            return new Snapshot(tree, included.size(), bytes);
        } catch (IOException e) {
            throw new ServerGitException("작업 트리 스냅샷을 만들지 못했습니다", e);
        }
    }

    /** 1패스: 헤더만 읽는다. 원시 상한을 넘으면 즉시 멈춘다. */
    private Plan plan(Supplier<InputStream> archive, Map<String, BaseEntry> basePaths) {
        Map<String, Header> headers = new HashMap<>();
        Map<String, byte[]> gitignores = new HashMap<>();
        long rawBytes = 0;
        int order = 0;
        try (InputStream raw = archive.get();
             TarArchiveInputStream tar = new TarArchiveInputStream(bounded(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String path = stripTopDirectory(entry.getName());
                if (path == null || entry.isDirectory()) {
                    continue;
                }
                requireSafePath(path);
                boolean tracked = basePaths.containsKey(path);
                if (insideGitlink(path, basePaths) || (!tracked && hasExcludedSegment(path))) {
                    continue;
                }
                Kind kind;
                String linkTarget = null;
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    continue;   // commons-compress 는 이것들도 isFile 로 본다
                } else if (entry.isLink()) {
                    kind = Kind.HARDLINK;
                    linkTarget = stripTopDirectory(entry.getLinkName());
                } else if (entry.isSymbolicLink()) {
                    kind = Kind.SYMLINK;
                    linkTarget = entry.getLinkName();
                } else if (entry.isFile()) {
                    kind = Kind.FILE;
                } else {
                    continue;
                }
                order++;
                rawBytes += kind == Kind.FILE ? entry.getSize() : 0;
                if (order > limits.rawMaxEntries() || rawBytes > limits.rawMaxBytes()) {
                    throw new SnapshotLimitExceededException(order, limits.rawMaxEntries(), rawBytes, limits.rawMaxBytes());
                }
                FileMode mode = (entry.getMode() & 0100) != 0 ? FileMode.EXECUTABLE_FILE : FileMode.REGULAR_FILE;
                headers.put(path, new Header(path, kind, mode, entry.getSize(), linkTarget, tracked, order));
                if (kind == Kind.FILE && (path.equals(".gitignore") || path.endsWith("/.gitignore"))) {
                    if (entry.getSize() > MAX_GITIGNORE_BYTES) {
                        throw new SnapshotLimitExceededException(order, limits.rawMaxEntries(), entry.getSize(), MAX_GITIGNORE_BYTES);
                    }
                    gitignores.put(parentOf(path), tar.readAllBytes());
                }
            }
            return new Plan(headers, gitignores);
        } catch (IOException e) {
            throw new ServerGitException("작업 트리 목록을 읽지 못했습니다", e);
        }
    }

    /** 2패스: 넣기로 정한 정규 파일만 본문을 읽어 저장한다. 1패스와 크기가 다르면 그 사이 바뀐 것이다. */
    private Map<String, ObjectId> readContents(Supplier<InputStream> archive, Plan plan, Set<String> needContent,
                                               ObjectInserter inserter) throws IOException {
        Map<String, ObjectId> blobs = new HashMap<>();
        try (InputStream raw = archive.get();
             TarArchiveInputStream tar = new TarArchiveInputStream(bounded(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null && blobs.size() < needContent.size()) {
                String path = stripTopDirectory(entry.getName());
                if (path == null || !needContent.contains(path) || !entry.isFile() || entry.isLink()
                        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    continue;
                }
                if (entry.getSize() != plan.headers().get(path).size()) {
                    throw new WorkTreeChangedException(path);
                }
                blobs.put(path, inserter.insert(Constants.OBJ_BLOB, tar.readAllBytes()));
            }
        }
        if (blobs.size() != needContent.size()) {
            throw new WorkTreeChangedException(needContent.stream().filter(p -> !blobs.containsKey(p)).findFirst().orElse(""));
        }
        return blobs;
    }

    private InputStream bounded(InputStream raw) {
        try {
            return BoundedInputStream.builder().setInputStream(raw).setMaxCount(limits.archiveMaxBytes())
                    .setOnMaxCount((max, count) -> {
                        throw new SnapshotLimitExceededException(0, limits.rawMaxEntries(), count, max);
                    }).get();
        } catch (IOException e) {
            throw new ServerGitException("작업 트리 스트림을 열지 못했습니다", e);
        }
    }

    /** base 와 새 트리 사이에 바뀐 경로. 이름 변경은 옛 경로·새 경로 둘 다 나온다. 지운 경로는 newBlob 이 없다. */
    public List<ChangedPath> changedPaths(RevCommit base, ObjectId tree) {
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(OutputStreamSink.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(false);
            List<ChangedPath> changes = new ArrayList<>();
            for (DiffEntry d : formatter.scan(iterator(reader, base == null ? null : base.getTree()), iterator(reader, tree))) {
                if (d.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    changes.add(new ChangedPath(d.getOldPath(), null));
                } else {
                    changes.add(new ChangedPath(d.getNewPath(), d.getNewId().toObjectId()));
                    if (d.getChangeType() == DiffEntry.ChangeType.MODIFY && !d.getOldPath().equals(d.getNewPath())) {
                        changes.add(new ChangedPath(d.getOldPath(), null));
                    }
                }
            }
            return changes;
        } catch (IOException e) {
            throw new ServerGitException("바뀐 경로를 계산하지 못했습니다", e);
        }
    }

    /** base 와 새 트리 사이의 unified diff. 지금 변경 내역(ChangeService)이 저장하는 형식과 같다. */
    public String diff(RevCommit base, ObjectId tree) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectReader reader = repository.newObjectReader(); DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(false);
            formatter.format(iterator(reader, base == null ? null : base.getTree()), iterator(reader, tree));
            formatter.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServerGitException("변경 내역을 만들지 못했습니다", e);
        }
    }

    /** base 위에 스냅샷 커밋 1개. 트리가 base 와 같으면 비어 있다. */
    public Optional<ObjectId> commit(RevCommit base, ObjectId tree, String message, PersonIdent author) {
        if (base != null && base.getTree().getId().equals(tree)) {
            return Optional.empty();
        }
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            if (base != null) {
                commit.setParentId(base);
            }
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage(message);
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return Optional.of(id);
        } catch (IOException e) {
            throw new ServerGitException("커밋을 만들지 못했습니다", e);
        }
    }

    /** {@code branch} 로 fast-forward push. 원격이 그 사이 움직였으면 {@link NonFastForwardException}. */
    public void push(String remoteUrl, CredentialsProvider credentials, ObjectId commit, String branch) {
        try (Git git = Git.wrap(repository)) {
            Iterable<PushResult> results = git.push().setRemote(remoteUrl).setCredentialsProvider(credentials)
                    .setRefSpecs(new RefSpec(commit.name() + ":" + Constants.R_HEADS + branch)).call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    switch (update.getStatus()) {
                        case OK, UP_TO_DATE -> { }
                        case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED ->
                                throw new NonFastForwardException(branch);
                        default -> throw new ServerGitException(
                                "push 가 거부됐습니다: " + update.getStatus() + " " + nullToEmpty(update.getMessage()), null);
                    }
                }
            }
        } catch (GitAPIException e) {
            throw new ServerGitException("push 하지 못했습니다: " + branch, e);
        }
    }

    public Repository repository() {
        return repository;
    }

    @Override
    public void close() {
        repository.close();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | java.io.UncheckedIOException ignored) {
            // 남은 디렉터리는 시작 시 정리 작업이 치운다(설계 6).
        }
    }

    // ---------------------------------------------------------------- 내부

    private Map<String, BaseEntry> readBasePaths(RevCommit base) {
        Map<String, BaseEntry> paths = new HashMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(base.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                paths.put(walk.getPathString(), new BaseEntry(walk.getFileMode(0), walk.getObjectId(0)));
            }
            return paths;
        } catch (IOException e) {
            throw new ServerGitException("기준 트리를 읽지 못했습니다", e);
        }
    }

    private static String stripTopDirectory(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        int slash = normalized.indexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return null;   // 최상위 디렉터리 자신
        }
        String rest = normalized.substring(slash + 1);
        return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
    }

    private static void requireSafePath(String path) {
        if (path.isEmpty() || path.startsWith("/")) {
            throw new ServerGitException("허용되지 않는 경로입니다: " + path, null);
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ServerGitException("허용되지 않는 경로입니다: " + path, null);
            }
        }
    }

    private static boolean hasExcludedSegment(String path) {
        for (String segment : path.split("/")) {
            if (ALWAYS_EXCLUDED_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideGitlink(String path, Map<String, BaseEntry> basePaths) {
        int slash = path.indexOf('/');
        while (slash > 0) {
            BaseEntry parent = basePaths.get(path.substring(0, slash));
            if (parent != null && parent.mode() == FileMode.GITLINK) {
                return true;
            }
            slash = path.indexOf('/', slash + 1);
        }
        return false;
    }

    private static Map<String, IgnoreNode> parseIgnores(Map<String, byte[]> gitignores) {
        Map<String, IgnoreNode> nodes = new HashMap<>();
        for (Map.Entry<String, byte[]> e : gitignores.entrySet()) {
            IgnoreNode node = new IgnoreNode();
            try {
                node.parse(new java.io.ByteArrayInputStream(e.getValue()));
            } catch (IOException ex) {
                throw new ServerGitException(".gitignore 를 읽지 못했습니다: " + e.getKey(), ex);
            }
            nodes.put(e.getKey(), node);
        }
        return nodes;
    }

    /** git 과 같은 판정: 상위 디렉터리 하나라도 무시되면 그 아래 전부 무시. 가장 깊은 규칙이 이긴다. */
    private static boolean isIgnored(String path, Map<String, IgnoreNode> nodes) {
        if (nodes.isEmpty()) {
            return false;
        }
        String[] segments = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                prefix.append('/');
            }
            prefix.append(segments[i]);
            boolean isDirectory = i < segments.length - 1;
            if (decide(prefix.toString(), isDirectory, nodes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean decide(String path, boolean isDirectory, Map<String, IgnoreNode> nodes) {
        String dir = parentOf(path);
        while (true) {
            IgnoreNode node = nodes.get(dir);
            if (node != null) {
                String relative = dir.isEmpty() ? path : path.substring(dir.length() + 1);
                Boolean ignored = node.checkIgnored(relative, isDirectory);
                if (ignored != null) {
                    return ignored;
                }
            }
            if (dir.isEmpty()) {
                return false;
            }
            dir = parentOf(dir);
        }
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static AbstractTreeIterator iterator(ObjectReader reader, ObjectId tree) throws IOException {
        if (tree == null) {
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, tree);
        return parser;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record BaseEntry(FileMode mode, ObjectId id) {
    }

    private enum Kind { FILE, SYMLINK, HARDLINK }

    private record Header(String path, Kind kind, FileMode mode, long size, String linkTarget, boolean tracked, int order) {
    }

    private record Plan(Map<String, Header> headers, Map<String, byte[]> gitignores) {
    }

    /** 두 번 읽는 사이 작업 트리가 바뀌었다. 다시 시도하면 된다. */
    public static class WorkTreeChangedException extends ServerGitException {
        public WorkTreeChangedException(String path) {
            super("스냅샷을 뜨는 동안 작업 트리가 바뀌었습니다: " + path, null);
        }
    }

    public record Snapshot(ObjectId tree, int fileCount, long bytes) {
    }

    public record ChangedPath(String path, ObjectId newBlob) {
    }

    /** scan 만 할 때 DiffFormatter 가 요구하는 출력 자리. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }
    }
}
