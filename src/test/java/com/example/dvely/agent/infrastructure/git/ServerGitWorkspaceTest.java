package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.git.GitTestFixtures.Remote;
import com.example.dvely.agent.infrastructure.git.GitTestFixtures.TarBuilder;
import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.ChangedPath;
import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.Snapshot;
import java.nio.charset.StandardCharsets;
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

        assertThatThrownBy(() -> small.snapshotTree(new TarBuilder()
                .file("a", "1").file("b", "2").file("c", "3").file("node_modules/x", "x").stream(), null))
                .isInstanceOf(SnapshotLimitExceededException.class)
                .hasMessageContaining("파일 3개 / 상한 2개")
                .hasMessageContaining(".gitignore");
    }

    @Test
    void anIgnoredFileLargerThanTheLimitDoesNotCount() {
        ServerGitWorkspace small = ServerGitWorkspace.create(root.resolve("small"), GitTestFixtures.limits(10, 100, root));

        Snapshot snapshot = small.snapshotTree(new TarBuilder()
                .file(".gitignore", "*.bin\n")
                .file("big.bin", "x".repeat(1000))
                .file("a.txt", "a").stream(), null);

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

    // ---------------------------------------------------------------- 도우미

    private RevCommit fetch(Map<String, String> files) throws Exception {
        remote.commit("preview", files);
        return workspace.fetchBase(remote.url(), null, "preview").orElseThrow();
    }

    private Snapshot snapshot(TarBuilder tar, RevCommit base) {
        return workspace.snapshotTree(tar.stream(), base);
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
