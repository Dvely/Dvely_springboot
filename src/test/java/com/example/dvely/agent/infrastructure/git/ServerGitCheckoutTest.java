package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.git.GitTestFixtures.Remote;
import com.example.dvely.agent.infrastructure.git.ServerGitCheckout.Target;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 컨테이너 clone 대신 서버가 만든 tar(#523 PR-3). 로컬 bare 원격으로 확인한다. */
class ServerGitCheckoutTest {

    @TempDir Path root;

    private Remote remote;
    private ServerGitCheckout checkout;

    @BeforeEach
    void setUp() throws Exception {
        remote = new Remote(root);
        checkout = new ServerGitCheckout(GitTestFixtures.limits(1000, 10_000_000, root.resolve("work")));
    }

    @Test
    void writesPreviewWithModesLinksExplicitDirectoriesAndTheMarker() throws Exception {
        remote.commit("main", Map.of("README.md", "main"));
        RevCommit preview = commit("preview", Map.of(
                "README.md", file("preview"),
                "bin/run.sh", new Blob(FileMode.EXECUTABLE_FILE, "#!/bin/sh"),
                "src/deep/a.txt", file("a"),
                "latest", new Blob(FileMode.SYMLINK, "README.md")));

        Archive archive = run(Target.branchesThenDefault("preview"), Map.of(".qeploy-source", bytes("octo/app")));

        assertThat(archive.commit).contains(preview.name());
        assertThat(archive.content(".qeploy-source")).isEqualTo("octo/app");
        assertThat(archive.content("app/README.md")).isEqualTo("preview");
        assertThat(archive.entry("app/bin/run.sh").getMode() & 0777).isEqualTo(0755);
        assertThat(archive.entry("app/README.md").getMode() & 0777).isEqualTo(0644);
        assertThat(archive.entry("app/latest").isSymbolicLink()).isTrue();
        assertThat(archive.entry("app/latest").getLinkName()).isEqualTo("README.md");
        // 상위 디렉터리가 파일보다 먼저, 모두 명시적으로 — 암묵적으로 만들어지면 소유자가 맞지 않을 수 있다
        assertThat(archive.names).containsSubsequence("app/", "app/src/", "app/src/deep/", "app/src/deep/a.txt");
        assertThat(archive.names).contains("app/bin/");
    }

    @Test
    void extrasAreBuiltFromTheCommitThatIsCheckedOut() throws Exception {
        // base 표시가 트리와 같은 tar 에 들어가야 둘이 어긋나지 않는다(#539)
        RevCommit preview = remote.commit("preview", Map.of("README.md", "p"));
        Archive archive = new Archive();
        java.util.List<String> seen = new ArrayList<>();
        archive.commit = checkout.checkout(remote.url(), null, Target.branchesThenDefault("preview"), "app",
                commit -> {
                    seen.add(commit);
                    return Map.of(".qeploy-base", bytes(commit));
                }, archive::read);

        assertThat(seen).containsExactly(preview.name());
        assertThat(archive.content(".qeploy-base")).isEqualTo(preview.name());
    }

    @Test
    void anEmptyRepositoryHandsNoCommitToTheExtras() throws Exception {
        Archive archive = new Archive();
        java.util.List<String> seen = new ArrayList<>();
        checkout.checkout(remote.url(), null, Target.branchesThenDefault("preview"), "app",
                commit -> {
                    seen.add(String.valueOf(commit));
                    return Map.of();
                }, archive::read);

        assertThat(seen).containsExactly("null");
    }

    @Test
    void fallsBackToTheDefaultBranchWithoutPreview() throws Exception {
        RevCommit main = remote.commit("main", Map.of("README.md", "main"));

        Archive archive = run(Target.branchesThenDefault("preview"), Map.of());

        assertThat(archive.commit).contains(main.name());
        assertThat(archive.content("app/README.md")).isEqualTo("main");
    }

    @Test
    void anEmptyRepositoryStillGetsAnEmptyAppDirectory() throws Exception {
        Archive archive = run(Target.branchesThenDefault("preview"), Map.of());

        assertThat(archive.commit).isEmpty();
        assertThat(archive.names).containsExactly("app/");
    }

    @Test
    void checksOutLightweightAndAnnotatedTags() throws Exception {
        RevCommit v1 = remote.commit("main", Map.of("README.md", "v1"));
        tag("v1", v1, false);
        RevCommit v2 = remote.commit("main", Map.of("README.md", "v2"));
        tag("v2", v2, true);
        remote.commit("main", Map.of("README.md", "v3"));

        assertThat(run(Target.tag("v1"), Map.of()).content("app/README.md")).isEqualTo("v1");
        Archive annotated = run(Target.tag("v2"), Map.of());
        assertThat(annotated.commit).contains(v2.name());
        assertThat(annotated.content("app/README.md")).isEqualTo("v2");
    }

    @Test
    void aMissingTagFailsInsteadOfFallingBackToTheBranch() throws Exception {
        remote.commit("main", Map.of("README.md", "main"));

        assertThatThrownBy(() -> run(Target.tag("v9"), Map.of()))
                .isInstanceOf(ServerGitException.class)
                .hasMessageContaining("v9");
    }

    @Test
    void aRepositoryOverTheLimitIsRefused() throws Exception {
        checkout = new ServerGitCheckout(GitTestFixtures.limits(2, 10_000_000, root.resolve("work")));
        remote.commit("main", Map.of("a", "1", "b", "2", "c", "3"));

        assertThatThrownBy(() -> run(Target.branchesThenDefault(), Map.of()))
                .isInstanceOf(ServerGitException.class)
                .hasMessageContaining("너무 커서");
    }

    @Test
    void theTemporaryWorkspaceIsGoneAfterwards() throws Exception {
        remote.commit("main", Map.of("README.md", "main"));

        run(Target.branchesThenDefault(), Map.of());

        try (var children = java.nio.file.Files.list(root.resolve("work"))) {
            assertThat(children).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Archive run(Target target, Map<String, byte[]> extras) {
        Archive archive = new Archive();
        archive.commit = checkout.checkout(remote.url(), null, target, "app", commit -> extras, archive::read);
        return archive;
    }

    private record Blob(FileMode mode, String content) {
    }

    private static Blob file(String content) {
        return new Blob(FileMode.REGULAR_FILE, content);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 모드를 지정해 branch 끝에 커밋한다(픽스처는 일반 파일만 만든다). */
    private RevCommit commit(String branch, Map<String, Blob> files) throws Exception {
        try (Repository repo = Git.open(remote.bare.toFile()).getRepository();
             ObjectInserter inserter = repo.newObjectInserter();
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, Blob> file : new java.util.TreeMap<>(files).entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(file.getValue().mode());
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, bytes(file.getValue().content())));
                builder.add(entry);
            }
            builder.finish();
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            ObjectId parent = repo.resolve("refs/heads/" + branch);
            if (parent != null) {
                commit.setParentId(parent);
            }
            PersonIdent who = new PersonIdent("seed", "seed@test");
            commit.setAuthor(who);
            commit.setCommitter(who);
            commit.setMessage("seed");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repo.updateRef("refs/heads/" + branch);
            update.setNewObjectId(id);
            update.forceUpdate();
            return walk.parseCommit(id);
        }
    }

    private void tag(String name, RevCommit commit, boolean annotated) throws Exception {
        try (Repository repo = Git.open(remote.bare.toFile()).getRepository();
             ObjectInserter inserter = repo.newObjectInserter()) {
            ObjectId target = commit.getId();
            if (annotated) {
                TagBuilder tag = new TagBuilder();
                tag.setObjectId(commit);
                tag.setTag(name);
                tag.setTagger(new PersonIdent("seed", "seed@test"));
                tag.setMessage("release " + name);
                target = inserter.insert(tag);
                inserter.flush();
            }
            RefUpdate update = repo.updateRef(Constants.R_TAGS + name);
            update.setNewObjectId(target);
            update.forceUpdate();
        }
    }

    private static final class Archive {
        Optional<String> commit;
        final List<String> names = new ArrayList<>();
        final Map<String, TarArchiveEntry> entries = new LinkedHashMap<>();
        final Map<String, String> contents = new LinkedHashMap<>();

        void read(InputStream in) {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    names.add(entry.getName());
                    entries.put(entry.getName(), entry);
                    if (entry.isFile()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        tar.transferTo(out);
                        contents.put(entry.getName(), out.toString(StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        TarArchiveEntry entry(String name) {
            return entries.get(name);
        }

        String content(String name) {
            return contents.get(name);
        }
    }
}
