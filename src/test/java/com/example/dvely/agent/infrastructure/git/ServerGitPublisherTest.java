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
                Optional.empty(), true, Set.of());

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

        Published published = publisher.publish(request(new TarBuilder().file("README.md", "v1")), Optional.empty(), true, Set.of());

        assertThat(published.commit()).isEmpty();
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());
    }

    @Test
    void fallsBackToMainAndCreatesPreviewWhenItDoesNotExist() throws Exception {
        RevCommit main = remote.commit("main", Map.of("README.md", "main"));

        publisher.publish(request(new TarBuilder().file("README.md", "changed")), Optional.empty(), true, Set.of());

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
                "feat: apply Qeploy Agent task t-1"), Optional.empty(), true, Set.of());

        assertThat(opened.get()).isEqualTo(4);   // 시도마다 두 번(목록·본문), 재시도 1회
        assertThat(remote.files("preview")).containsEntry("README.md", "mine");
        // 두 번째 시도는 움직인 끝 위에 쌓는다 — 남이 올린 커밋을 덮어쓰지 않는다.
        assertThat(remote.tip("preview").getParent(0).getId()).isEqualTo(moved.get().getId());
    }

    @Test
    void anApprovedTreeIsPushedOnlyIfTheWorkTreeIsStillTheSame() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));
        Inspection inspected = publisher.inspect(request(new TarBuilder().file("README.md", "reviewed")), Set.of());

        assertThatThrownBy(() -> publisher.publish(request(new TarBuilder().file("README.md", "changed later")),
                Optional.of(inspected.tree()), true, Set.of()))
                .isInstanceOf(ServerGitPublisher.SnapshotChangedException.class);
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());

        publisher.publish(request(new TarBuilder().file("README.md", "reviewed")), Optional.of(inspected.tree()), true, Set.of());
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

    @Test
    void aProtectedChangeIsRefusedUnlessTheCallerAllowsIt() throws Exception {
        RevCommit base = remote.commit("preview", Map.of("README.md", "v1"));
        TarBuilder tar = new TarBuilder().file("README.md", "v1").file(".github/workflows/ci.yml", "on: push");

        assertThatThrownBy(() -> publisher.publish(request(tar), Optional.empty(), false, Set.of()))
                .isInstanceOf(ServerGitPublisher.ProtectedChangeRequiresApprovalException.class)
                .hasMessageContaining(".github/workflows/ci.yml");
        assertThat(remote.tip("preview").getId()).isEqualTo(base.getId());

        publisher.publish(request(tar), Optional.empty(), true, Set.of());
        assertThat(remote.files("preview")).containsKey(".github/workflows/ci.yml");
    }

    @Test
    void publishesToTheSameRepositoryRunOneAtATime() throws Exception {
        remote.commit("preview", Map.of("README.md", "v1"));
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Published>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                byte[] tar = new TarBuilder().file("README.md", "writer-" + i).bytes();
                Supplier<InputStream> archive = () -> {
                    // 잠금 안에서만 열린다 — 동시에 안에 있는 수가 1을 넘으면 직렬화가 깨진 것이다.
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return new java.io.ByteArrayInputStream(tar);
                };
                Request request = new Request(remote.url(), null, "preview", List.of("preview", "main"), archive, "m");
                results.add(pool.submit(() -> publisher.publish(request, Optional.empty(), true, Set.of())));
            }
            for (var result : results) {
                assertThat(result.get(60, java.util.concurrent.TimeUnit.SECONDS).commit()).isPresent();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(remote.files("preview").get("README.md")).startsWith("writer-");
    }

    @Test
    void eachCommitCarriesTheTimeItWasMade() throws Exception {
        // 작성자 객체를 상수로 두면 처음 만든 시각이 모든 커밋에 찍힌다.
        remote.commit("preview", Map.of("README.md", "v1"));
        java.time.Instant before = java.time.Instant.now().minusSeconds(1);
        Thread.sleep(1100);   // 커밋 시각은 초 단위다

        publisher.publish(request(new TarBuilder().file("README.md", "v2")), Optional.empty(), true, Set.of());

        java.time.Instant committed = remote.tip("preview").getCommitterIdent().getWhenAsInstant();
        assertThat(committed).isAfter(before.plusSeconds(1));
        assertThat(remote.tip("preview").getAuthorIdent().getWhenAsInstant()).isEqualTo(committed);
    }
}
