package com.example.dvely.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * I45 (#45): deterministic, thread-free reproduction of the "case B" lost-update scenario the
 * design's §0 analysis is built on. This test class has no {@code @Transactional} (and no
 * background thread juggling), so every repository call below is its own auto-commit
 * transaction — exactly mirroring how {@code DeploymentCommandService.execute()} (no
 * surrounding transaction) reads/writes a {@code Project}: each {@code findById}-style call does
 * a genuinely fresh read from the DB rather than hitting the Spring-managed L1 cache a
 * {@code @Transactional} test would. Real MySQL, real {@code ProjectRepositoryAdapter}/
 * {@code ProjectEntity} — proves the adapter-level version guard (not merely Hibernate's
 * {@code @Version}, which alone cannot catch this exact case — see the adapter's javadoc) stops
 * a lost update against the live schema, no threads required to reproduce it.
 */
@SpringBootTest
class ProjectOptimisticLockIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void secondSaveOfAnOlderSnapshotFailsWithOptimisticLockingFailure() {
        User owner = userRepository.save(new User(new GithubId("i45-lock-test-" + System.nanoTime()), "octo", null));
        Project created = projectRepository.save(
                new Project(owner.getId(), "lock-test-project", "blank", null, "fast", RepositoryVisibility.PRIVATE)
        );
        Long projectId = created.getId();

        // Two independent reads of the same row. With no surrounding transaction, each is a
        // fresh SELECT against the DB (design §0's "case B") — both snapshots genuinely carry
        // the same version at this point, not merely an L1-cache-shared reference.
        Project snapshotA = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();
        Project snapshotB = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();
        assertThat(snapshotA.getVersion()).isEqualTo(snapshotB.getVersion());

        snapshotA.rename("renamed-by-a");
        Project afterA = projectRepository.save(snapshotA);
        assertThat(afterA.getVersion()).isEqualTo(snapshotA.getVersion() + 1);

        // B is still holding the pre-A-save version — saving it must be rejected instead of
        // silently overwriting A's already-committed change (the lost-update this whole design
        // exists to prevent).
        snapshotB.rename("renamed-by-b");
        assertThatThrownBy(() -> projectRepository.save(snapshotB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Project finalState = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();
        assertThat(finalState.getName()).isEqualTo("renamed-by-a");
    }
}
