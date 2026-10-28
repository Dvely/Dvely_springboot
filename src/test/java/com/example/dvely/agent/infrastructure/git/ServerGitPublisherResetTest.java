package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.git.GitTestFixtures.Remote;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 거절 REVERT(#532): preview 를 main 과 같은 내용으로 되돌리는 커밋. */
class ServerGitPublisherResetTest {

    @TempDir Path root;

    private Remote remote;
    private ServerGitPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        remote = new Remote(root);
        publisher = new ServerGitPublisher(GitTestFixtures.limits(1000, 10_000_000, root.resolve("work")),
                new ProtectedPathPolicy(Set.of()));
    }

    @Test
    void pushesAMergeCommitWhoseTreeIsMainOnTopOfPreview() throws Exception {
        RevCommit main = remote.commit("main", Map.of("README.md", "v1"));
        remote.branch("preview", "main");
        RevCommit rejected = remote.commit("preview", Map.of("README.md", "v2", "a.txt", "a"));

        Optional<ObjectId> pushed = publisher.resetTo(remote.url(), null, "preview", "main", "revert: preview 를 main 상태로 되돌림 (approval 7)");

        RevCommit tip = remote.tip("preview");
        assertThat(pushed).contains(tip.getId());
        // 첫 부모가 이전 preview 라 fast-forward 이고(강제 push 아님), 두 번째 부모가 main 이라 main...preview 비교가 빈다.
        assertThat(tip.getParentCount()).isEqualTo(2);
        assertThat(tip.getParent(0).getId()).isEqualTo(rejected.getId());
        assertThat(tip.getParent(1).getId()).isEqualTo(main.getId());
        assertThat(tip.getFullMessage()).isEqualTo("revert: preview 를 main 상태로 되돌림 (approval 7)");
        assertThat(remote.files("preview")).isEqualTo(Map.of("README.md", "v1"));
        assertThat(remote.tip("main").getId()).isEqualTo(main.getId());
    }

    @Test
    void nothingIsPushedWhenPreviewAlreadyMatchesMain() throws Exception {
        remote.commit("main", Map.of("README.md", "v1"));
        remote.branch("preview", "main");
        remote.commit("preview", Map.of("README.md", "v2"));
        publisher.resetTo(remote.url(), null, "preview", "main", "revert: 1");
        RevCommit afterFirst = remote.tip("preview");

        Optional<ObjectId> second = publisher.resetTo(remote.url(), null, "preview", "main", "revert: 2");

        assertThat(second).isEmpty();
        assertThat(remote.tip("preview").getId()).isEqualTo(afterFirst.getId());
    }

    @Test
    void nothingHappensWithoutAPreviewBranch() throws Exception {
        RevCommit main = remote.commit("main", Map.of("README.md", "v1"));

        assertThat(publisher.resetTo(remote.url(), null, "preview", "main", "revert")).isEmpty();
        assertThat(remote.tip("preview")).isNull();
        assertThat(remote.tip("main").getId()).isEqualTo(main.getId());
    }
}
