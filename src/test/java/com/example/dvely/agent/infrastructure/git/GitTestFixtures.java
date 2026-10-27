package com.example.dvely.agent.infrastructure.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** 테스트용 tar·원격 저장소 도우미. 원격은 로컬 bare 저장소이고 file URI 로 접근한다. */
final class GitTestFixtures {

    private GitTestFixtures() {
    }

    static ServerGitProperties limits(int maxFiles, long maxBytes, Path workDir) {
        return new ServerGitProperties(false, false, maxFiles, maxBytes, workDir.toString());
    }

    /** docker archive 처럼 첫 구간이 디렉터리 이름(app/)인 tar. */
    static final class TarBuilder {
        private final Map<String, Object[]> entries = new LinkedHashMap<>();

        TarBuilder file(String path, String content) {
            entries.put(path, new Object[]{"file", content, 0644});
            return this;
        }

        TarBuilder executable(String path, String content) {
            entries.put(path, new Object[]{"file", content, 0755});
            return this;
        }

        TarBuilder symlink(String path, String target) {
            entries.put(path, new Object[]{"link", target, 0777});
            return this;
        }

        TarBuilder device(String path) {
            entries.put(path, new Object[]{"device", "", 0644});
            return this;
        }

        TarBuilder raw(String rawName, String content) {
            entries.put("\0raw:" + rawName, new Object[]{"file", content, 0644});
            return this;
        }

        byte[] bytes() {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.putArchiveEntry(new TarArchiveEntry("app/"));
                tar.closeArchiveEntry();
                for (Map.Entry<String, Object[]> e : entries.entrySet()) {
                    String name = e.getKey().startsWith("\0raw:") ? e.getKey().substring(5) : "app/" + e.getKey();
                    String kind = (String) e.getValue()[0];
                    String content = (String) e.getValue()[1];
                    int mode = (int) e.getValue()[2];
                    TarArchiveEntry entry = switch (kind) {
                        case "link" -> {
                            TarArchiveEntry link = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
                            link.setLinkName(content);
                            yield link;
                        }
                        case "device" -> new TarArchiveEntry(name, TarConstants.LF_CHR);
                        default -> new TarArchiveEntry(name);
                    };
                    entry.setMode(mode);
                    byte[] data = kind.equals("file") ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
                    if (kind.equals("file")) {
                        entry.setSize(data.length);
                    }
                    tar.putArchiveEntry(entry);
                    tar.write(data);
                    tar.closeArchiveEntry();
                }
                tar.finish();
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        InputStream stream() {
            return new ByteArrayInputStream(bytes());
        }
    }

    /** 로컬 bare 원격. 커밋은 작업 트리 없이 객체로 직접 만든다 — ignore 대상도 "추적 중"으로 넣을 수 있다. */
    static final class Remote {
        final Path bare;

        Remote(Path root) throws Exception {
            this.bare = root.resolve("remote.git");
            Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        }

        String url() {
            return bare.toUri().toString();
        }

        /** branch 끝에 files(그 브랜치의 전체 내용)를 커밋한다. 브랜치가 없으면 만든다. */
        RevCommit commit(String branch, Map<String, String> files) throws Exception {
            try (Repository repo = Git.open(bare.toFile()).getRepository();
                 ObjectInserter inserter = repo.newObjectInserter();
                 RevWalk walk = new RevWalk(repo)) {
                DirCache index = DirCache.newInCore();
                DirCacheBuilder builder = index.builder();
                new java.util.TreeMap<>(files).forEach((path, content) -> {
                    DirCacheEntry entry = new DirCacheEntry(path);
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    try {
                        entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    builder.add(entry);
                });
                builder.finish();
                ObjectId tree = index.writeTree(inserter);
                CommitBuilder commit = new CommitBuilder();
                commit.setTreeId(tree);
                ObjectId parent = repo.resolve("refs/heads/" + branch);
                if (parent != null) {
                    commit.setParentId(parent);
                }
                PersonIdent who = new PersonIdent("seed", "seed@test");
                commit.setAuthor(who);
                commit.setCommitter(who);
                commit.setMessage("seed " + branch);
                ObjectId id = inserter.insert(commit);
                inserter.flush();
                RefUpdate update = repo.updateRef("refs/heads/" + branch);
                update.setNewObjectId(id);
                update.forceUpdate();
                return walk.parseCommit(id);
            }
        }

        RevCommit tip(String branch) throws Exception {
            try (Repository repo = Git.open(bare.toFile()).getRepository(); RevWalk walk = new RevWalk(repo)) {
                ObjectId id = repo.resolve("refs/heads/" + branch);
                return id == null ? null : walk.parseCommit(id);
            }
        }

        Map<String, String> files(String branch) throws Exception {
            try (Repository repo = Git.open(bare.toFile()).getRepository(); RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(repo.resolve("refs/heads/" + branch));
                Map<String, String> files = new LinkedHashMap<>();
                try (TreeWalk tree = new TreeWalk(repo)) {
                    tree.addTree(commit.getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        files.put(tree.getPathString(),
                                new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
                    }
                }
                return files;
            }
        }
    }
}
