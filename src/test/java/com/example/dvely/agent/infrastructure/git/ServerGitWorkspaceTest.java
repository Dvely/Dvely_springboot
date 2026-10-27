package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.git.GitTestFixtures.Remote;
import com.example.dvely.agent.infrastructure.git.GitTestFixtures.TarBuilder;
import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.ChangedPath;
import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 스냅샷 → 트리 규칙(설계 3.1·5). 포함 규칙은 git add -A 와 같다. */
class ServerGitWorkspaceTest {

    @TempDir Path root;

    private Remote remote;
    private ServerGitWorkspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        remote = new Remote(root);
        workspace = ServerGitWorkspace.create(root.resolve("work"), GitTestFixtures.limits(1000, 10_000_000, root));
    }

    @Test
    void newGitAndNodeModulesAreLeftOut() {
        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file("src/App.vue", "app")
                .file(".git/config", "[core]")
                .file("node_modules/lib/index.js", "lib")
                .file("packages/a/node_modules/x.js", "x"), null));

        assertThat(tree).containsOnlyKeys("src/App.vue");
    }

    @Test
    void hierarchicalGitignoreAppliesToNewPathsIncludingNegation() {
        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file(".gitignore", "dist/\n*.log\n!keep.log\n")
                .file("web/.gitignore", "cache/\n")
                .file("dist/app.js", "built")
                .file("debug.log", "noise")
                .file("keep.log", "kept")
                .file("web/cache/x", "x")
                .file("web/index.html", "<html>"), null));

        assertThat(tree).containsOnlyKeys(".gitignore", "web/.gitignore", "keep.log", "web/index.html");
    }

    @Test
    void aTrackedFileStaysEvenWhenAnIgnoreRuleMatchesIt() throws Exception {
        RevCommit base = fetch(Map.of(".gitignore", "dist/\n.env*\n", "dist/app.js", "v1", ".env.example", "A=1"));

        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file(".gitignore", "dist/\n.env*\n")
                .file("dist/app.js", "v2")
                .file(".env.example", "A=1")
                .file(".env", "SECRET=1"), base));

        assertThat(tree).containsEntry("dist/app.js", "v2").containsEntry(".env.example", "A=1")
                .doesNotContainKey(".env");
    }

    @Test
    void addingAnIgnoreLineDoesNotDeleteTrackedFiles() throws Exception {
        RevCommit base = fetch(Map.of("src/App.vue", "app", "docs/guide.md", "guide"));

        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file(".gitignore", "src/\ndocs/\n")
                .file("src/App.vue", "app")
                .file("docs/guide.md", "guide"), base));

        assertThat(tree).containsKeys("src/App.vue", "docs/guide.md", ".gitignore");
    }

    @Test
    void aTrackedFileMissingFromTheWorkTreeIsDeleted() throws Exception {
        RevCommit base = fetch(Map.of("a.txt", "a", "b.txt", "b"));

        Map<String, String> tree = treeOf(snapshot(new TarBuilder().file("a.txt", "a"), base));

        assertThat(tree).containsOnlyKeys("a.txt");
    }

    @Test
    void trackedNodeModulesAreKeptAndUpdated() throws Exception {
        RevCommit base = fetch(Map.of("node_modules/vendored/index.js", "v1", "app.js", "a"));

        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file("node_modules/vendored/index.js", "v2")
                .file("node_modules/other/index.js", "new")
                .file("app.js", "a"), base));

        assertThat(tree).containsEntry("node_modules/vendored/index.js", "v2")
                .doesNotContainKey("node_modules/other/index.js");
    }

    @Test
    void executableBitAndSymlinksArePreservedWithoutTouchingTheDisk() throws Exception {
        Snapshot snapshot = snapshot(new TarBuilder()
                .executable("bin/run.sh", "#!/bin/sh")
                .file("README.md", "r")
                .symlink("link", "../../etc/passwd"), null);

        Map<String, FileMode> modes = modesOf(snapshot);
        assertThat(modes).containsEntry("bin/run.sh", FileMode.EXECUTABLE_FILE)
                .containsEntry("README.md", FileMode.REGULAR_FILE)
                .containsEntry("link", FileMode.SYMLINK);
        assertThat(treeOf(snapshot)).containsEntry("link", "../../etc/passwd");
        try (var files = Files.walk(root)) {
            assertThat(files.anyMatch(Files::isSymbolicLink)).isFalse();
        }
    }

    @Test
    void deviceEntriesAreSkipped() {
        assertThat(treeOf(snapshot(new TarBuilder().file("a.txt", "a").device("dev/null"), null)))
                .containsOnlyKeys("a.txt");
    }

    @Test
    void theSameInputGivesTheSameTree() {
        TarBuilder tar = new TarBuilder().file("a.txt", "a").executable("b.sh", "b");

        assertThat(snapshot(tar, null).tree()).isEqualTo(snapshot(tar, null).tree());
    }

    @Test
    void exceedingTheFileLimitFailsWithAMessageForTheUser() {
        ServerGitWorkspace small = ServerGitWorkspace.create(root.resolve("small"), GitTestFixtures.limits(2, 10_000_000, root));

        TarBuilder tar = new TarBuilder().file("a", "1").file("b", "2").file("c", "3").file("node_modules/x", "x");
        assertThatThrownBy(() -> small.snapshotTree(tar::stream, null))
                .isInstanceOf(SnapshotLimitExceededException.class)
                .hasMessageContaining("파일 3개 / 상한 2개")
                .hasMessageContaining(".gitignore");
    }

    @Test
    void anIgnoredFileLargerThanTheLimitDoesNotCount() {
        ServerGitWorkspace small = ServerGitWorkspace.create(root.resolve("small"), GitTestFixtures.limits(10, 100, root));

        TarBuilder tar = new TarBuilder()
                .file(".gitignore", "*.bin\n")
                .file("big.bin", "x".repeat(1000))
                .file("a.txt", "a");
        Snapshot snapshot = small.snapshotTree(tar::stream, null);

        assertThat(snapshot.fileCount()).isEqualTo(2);
    }

    @Test
    void pathsThatEscapeTheDirectoryAreRejected() {
        assertThatThrownBy(() -> snapshot(new TarBuilder().raw("app/../outside.txt", "x"), null))
                .isInstanceOf(ServerGitException.class);
    }

    @Test
    void changedPathsListBothSidesOfARenameAndDeletions() throws Exception {
        RevCommit base = fetch(Map.of("ci/deploy.yml", "x", "old.txt", "o"));
        Snapshot snapshot = snapshot(new TarBuilder().file(".github/workflows/deploy.yml", "x"), base);

        List<ChangedPath> changes = workspace.changedPaths(base, snapshot.tree());

        assertThat(changes).extracting(ChangedPath::path)
                .containsExactlyInAnyOrder("ci/deploy.yml", "old.txt", ".github/workflows/deploy.yml");
        assertThat(changes).filteredOn(c -> c.path().equals("old.txt")).allMatch(c -> c.newBlob() == null);
    }

    @Test
    void theDiffIsUnified() throws Exception {
        RevCommit base = fetch(Map.of("a.txt", "one\n"));
        Snapshot snapshot = snapshot(new TarBuilder().file("a.txt", "two\n"), base);

        assertThat(workspace.diff(base, snapshot.tree()))
                .contains("diff --git a/a.txt b/a.txt", "-one", "+two");
    }

    @Test
    void anUnchangedTreeProducesNoCommit() throws Exception {
        RevCommit base = fetch(Map.of("a.txt", "a"));
        Snapshot snapshot = snapshot(new TarBuilder().file("a.txt", "a"), base);

        assertThat(workspace.commit(base, snapshot.tree(), "m", ServerGitPublisher.AGENT)).isEmpty();
    }

    // ---------------------------------------------------------------- 받아들이는 양의 상한(2패스)

    @Test
    void ignoredBulkFilesNeverBecomeBlobsOnTheServer() throws Exception {
        TarBuilder tar = new TarBuilder().file(".gitignore", "cache/\n").file("a.txt", "keep-me");
        for (int i = 0; i < 50; i++) {
            tar.file("cache/f" + i, "cached-content-" + i);
        }

        snapshot(tar, null);

        var objects = workspace.repository().getObjectDatabase();
        assertThat(objects.has(ProtectedPathPolicy.blobIdOf("keep-me"))).isTrue();
        for (int i = 0; i < 50; i++) {
            assertThat(objects.has(ProtectedPathPolicy.blobIdOf("cached-content-" + i))).isFalse();
        }
    }

    @Test
    void theRawEntryCapStopsBeforeReadingTheWholeArchive() {
        ServerGitWorkspace capped = ServerGitWorkspace.create(root.resolve("capped"),
                GitTestFixtures.limits(1000, 10_000_000, root, 5, 1L << 30, 1L << 32));
        TarBuilder tar = new TarBuilder();
        for (int i = 0; i < 200; i++) {
            tar.file("f" + i, "x".repeat(500));
        }
        byte[] bytes = tar.bytes();
        AtomicLong read = new AtomicLong();

        assertThatThrownBy(() -> capped.snapshotTree(() -> counting(bytes, read), null))
                .isInstanceOf(SnapshotLimitExceededException.class);
        assertThat(read.get()).isLessThan(bytes.length / 4);
    }

    @Test
    void theRawByteCapCountsFilesThatWouldLaterBeIgnored() {
        ServerGitWorkspace capped = ServerGitWorkspace.create(root.resolve("capped"),
                GitTestFixtures.limits(1000, 10_000_000, root, 100_000, 2_000, 1L << 32));
        TarBuilder tar = new TarBuilder().file(".gitignore", "dist/\n");
        for (int i = 0; i < 10; i++) {
            tar.file("dist/chunk" + i + ".js", "y".repeat(500));
        }

        assertThatThrownBy(() -> capped.snapshotTree(tar::stream, null)).isInstanceOf(SnapshotLimitExceededException.class);
    }

    @Test
    void theArchiveStreamItselfHasAReadCap() {
        ServerGitWorkspace capped = ServerGitWorkspace.create(root.resolve("capped"),
                GitTestFixtures.limits(1000, 10_000_000, root, 100_000, 1L << 30, 4_096));
        TarBuilder tar = new TarBuilder();
        for (int i = 0; i < 20; i++) {
            tar.file("node_modules/pkg/f" + i, "z".repeat(1000));   // 걸러지는 경로도 스트림으로는 지나간다
        }

        assertThatThrownBy(() -> capped.snapshotTree(tar::stream, null)).isInstanceOf(SnapshotLimitExceededException.class);
    }

    @Test
    void aWorkTreeThatChangesBetweenThePassesIsRejected() {
        byte[] first = new TarBuilder().file("a.txt", "short").bytes();
        byte[] second = new TarBuilder().file("a.txt", "much longer now").bytes();
        AtomicLong opened = new AtomicLong();

        assertThatThrownBy(() -> workspace.snapshotTree(
                () -> new ByteArrayInputStream(opened.getAndIncrement() == 0 ? first : second), null))
                .isInstanceOf(ServerGitWorkspace.WorkTreeChangedException.class);
    }

    // ---------------------------------------------------------------- 하드링크

    @Test
    void aTrackedHardlinkKeepsItsContentFromTheEarlierFile() throws Exception {
        RevCommit base = fetch(Map.of("a.txt", "same", "b.txt", "same"));

        Map<String, String> tree = treeOf(snapshot(new TarBuilder()
                .file("a.txt", "same").hardlink("b.txt", "a.txt"), base));

        assertThat(tree).containsEntry("a.txt", "same").containsEntry("b.txt", "same");
    }

    @Test
    void aTrackedHardlinkThatCannotBeResolvedFailsInsteadOfDeleting() throws Exception {
        RevCommit base = fetch(Map.of("b.txt", "x"));

        assertThatThrownBy(() -> snapshot(new TarBuilder().hardlink("b.txt", "missing.txt"), base))
                .isInstanceOf(ServerGitException.class).hasMessageContaining("하드링크");
    }

    @Test
    void anUntrackedUnresolvableHardlinkIsSkipped() {
        assertThat(treeOf(snapshot(new TarBuilder().file("a.txt", "a").hardlink("new.txt", "missing.txt"), null)))
                .containsOnlyKeys("a.txt");
    }

    private static InputStream counting(byte[] bytes, AtomicLong read) {
        return new java.io.FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read() throws java.io.IOException {
                int b = super.read();
                if (b >= 0) {
                    read.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws java.io.IOException {
                int n = super.read(buffer, off, len);
                if (n > 0) {
                    read.addAndGet(n);
                }
                return n;
            }
        };
    }

    // ---------------------------------------------------------------- 도우미

    private RevCommit fetch(Map<String, String> files) throws Exception {
        remote.commit("preview", files);
        return workspace.fetchBase(remote.url(), null, "preview").orElseThrow();
    }

    private Snapshot snapshot(TarBuilder tar, RevCommit base) {
        byte[] bytes = tar.bytes();
        return workspace.snapshotTree(() -> new ByteArrayInputStream(bytes), base);
    }

    private Map<String, String> treeOf(Snapshot snapshot) {
        Map<String, String> files = new HashMap<>();
        Repository repo = workspace.repository();
        try (TreeWalk walk = new TreeWalk(repo)) {
            walk.addTree(snapshot.tree());
            walk.setRecursive(true);
            while (walk.next()) {
                files.put(walk.getPathString(), new String(repo.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
            return files;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, FileMode> modesOf(Snapshot snapshot) throws Exception {
        Map<String, FileMode> modes = new HashMap<>();
        try (TreeWalk walk = new TreeWalk(workspace.repository())) {
            walk.addTree(snapshot.tree());
            walk.setRecursive(true);
            while (walk.next()) {
                modes.put(walk.getPathString(), walk.getFileMode(0));
            }
        }
        return modes;
    }
}
