package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.git.GitTestFixtures.Remote;
import com.example.dvely.agent.infrastructure.git.GitTestFixtures.TarBuilder;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Inspection;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Published;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Request;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 검토·반영 흐름을 로컬 bare 원격으로 확인한다(설계 3.1·3.3·5). */
class ServerGitPublisherTest {

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
    void publishesOneSnapshotCommitOnTopOfPreview() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));

        Published published = publisher.publish(request(new TarBuilder().file("README.md", "v2").file("a.txt", "a")),
                Optional.empty());

        RevCommit tip = remote.tip("preview");
        assertThat(published.commit()).contains(tip.getId());
        assertThat(tip.getParent(0).getId()).isEqualTo(base.getId());
        assertThat(tip.getAuthorIdent().getEmailAddress()).isEqualTo("agent@qeploy.com");
        assertThat(tip.getFullMessage()).isEqualTo("feat: apply Qeploy Agent task t-1");
        assertThat(remote.files("preview")).containsEntry("README.md", "v2").containsEntry("a.txt", "a");
    }

    @Test
    void nothingIsPushedWhenTheTreeIsUnchanged() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));

        Published published = publisher.publish(request(new TarBuilder().file("README.md", "v1")), Optional.empty());

        assertThat(published.commit()).isEmpty();
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());
    }

    @Test
    void fallsBackToMainAndCreatesPreviewWhenItDoesNotExist() throws Exception {
        RevCommit main = remote.commit("main", Map.of("README.md", "main"));

        publisher.publish(request(new TarBuilder().file("README.md", "changed")), Optional.empty());

        assertThat(remote.tip("preview").getParent(0).getId()).isEqualTo(main.getId());
        assertThat(remote.tip("main").getId()).isEqualTo(main.getId());
    }

    @Test
    void retriesOnceWhenPreviewMovedDuringThePush() throws Exception {
        remote.commit("preview", Map.of("README.md", "v1"));
        AtomicInteger opened = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RevCommit> moved = new java.util.concurrent.atomic.AtomicReference<>();
        byte[] tar = new TarBuilder().file("README.md", "mine").bytes();
        Supplier<InputStream> archive = () -> {
            if (opened.getAndIncrement() == 0) {
                try {
                    moved.set(remote.commit("preview", Map.of("README.md", "someone-else")));   // 그 사이 원격이 움직인다
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return new java.io.ByteArrayInputStream(tar);
        };

        publisher.publish(new Request(remote.url(), null, "preview", List.of("preview", "main"), archive,
                "feat: apply Qeploy Agent task t-1"), Optional.empty());

        assertThat(opened.get()).isEqualTo(2);
        assertThat(remote.files("preview")).containsEntry("README.md", "mine");
        // 두 번째 시도는 움직인 끝 위에 쌓는다 — 남이 올린 커밋을 덮어쓰지 않는다.
        assertThat(remote.tip("preview").getParent(0).getId()).isEqualTo(moved.get().getId());
    }

    @Test
    void anApprovedTreeIsPushedOnlyIfTheWorkTreeIsStillTheSame() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));
        Inspection inspected = publisher.inspect(request(new TarBuilder().file("README.md", "reviewed")), Set.of());

        assertThatThrownBy(() -> publisher.publish(request(new TarBuilder().file("README.md", "changed later")),
                Optional.of(inspected.tree())))
                .isInstanceOf(ServerGitPublisher.SnapshotChangedException.class);
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());

        publisher.publish(request(new TarBuilder().file("README.md", "reviewed")), Optional.of(inspected.tree()));
        assertThat(remote.files("preview")).containsEntry("README.md", "reviewed");
    }

    @Test
    void inspectionReportsProtectedPathsWithoutPushing() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));

        Inspection inspection = publisher.inspect(request(new TarBuilder()
                .file("README.md", "v1").file(".github/workflows/ci.yml", "on: push")), Set.of());

        assertThat(inspection.changedPaths()).containsExactly(".github/workflows/ci.yml");
        assertThat(inspection.requiringApproval()).containsExactly(".github/workflows/ci.yml");
        assertThat(inspection.diff()).contains("+on: push");
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());
    }

    private Request request(TarBuilder tar) {
        byte[] bytes = tar.bytes();
        return new Request(remote.url(), null, "preview", List.of("preview", "main"),
                () -> new java.io.ByteArrayInputStream(bytes), "feat: apply Qeploy Agent task t-1");
    }
}
