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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /**
     * I45 (#45) review follow-up F5: the "case A" half of the design's §0 analysis (a
     * {@code @Transactional} service method's {@code find} populates the persistence context, so
     * the adapter's own {@code findById} is an L1-cache hit — meaning it never disagrees with the
     * domain object's carried version at read time, and the actual conflict is caught purely by
     * Hibernate's own {@code @Version} flush check, not the adapter's manual comparison) was
     * previously only asserted by inference, never actually reproduced. This test drives two real,
     * separately-committed transactions against the same row with manual
     * {@link PlatformTransactionManager} control (no threads needed — transaction 2 is opened,
     * reads and caches the row, is deliberately left uncommitted while transaction 1 runs to
     * completion, then transaction 2 resumes and is forced to flush a now-stale version at
     * commit time).
     */
    @Test
    void aTransactionalReadThenLateCommitConflictsWithHibernatesOwnVersionCheckAtFlushTime() {
        User owner = userRepository.save(new User(new GithubId("i45-lock-test-casea-" + System.nanoTime()), "octo", null));
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Long projectId = txTemplate.execute(status -> projectRepository.save(
                new Project(owner.getId(), "case-a-project", "blank", null, "fast", RepositoryVisibility.PRIVATE)
        ).getId());

        // "Transaction 2" begins first and reads the row — this find() populates *its own*
        // persistence context's L1 cache with the current (pre-tx1) version, and is deliberately
        // left open (not committed yet), simulating a second concurrent request that started
        // before the first one finished.
        TransactionStatus tx2 = transactionManager.getTransaction(new DefaultTransactionDefinition());
        Project readByTx2 = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();

        // "Transaction 1" runs to completion and commits in between — genuinely separate from
        // tx2, not merely nested inside it. PROPAGATION_REQUIRES_NEW is essential here: tx2 is
        // still open on this same thread, so a plain PROPAGATION_REQUIRED template would just
        // join it (one shared persistence context, one flush, no real conflict to observe) —
        // REQUIRES_NEW suspends tx2's transactional resources for the duration of this block and
        // resumes them afterward, giving tx1 its own physical transaction and commit.
        TransactionTemplate requiresNewTemplate = new TransactionTemplate(transactionManager);
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.execute(status -> {
            Project readByTx1 = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();
            readByTx1.rename("renamed-by-tx1");
            projectRepository.save(readByTx1);
            return null;
        });

        // Resume tx2: mutate the snapshot it read *before* tx1 committed, and save it. Inside
        // tx2's still-open persistence context, the adapter's own findById() is an L1-cache hit
        // returning the *same* managed instance readByTx2 came from — so the adapter's manual
        // version comparison sees matching (both still pre-tx1) versions and does not throw here.
        // The row's real version has moved on in the database, though, so the conflict can only
        // be caught when this transaction actually flushes.
        readByTx2.rename("renamed-by-tx2");
        projectRepository.save(readByTx2);

        assertThatThrownBy(() -> transactionManager.commit(tx2))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Project finalState = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, owner.getId()).orElseThrow();
        assertThat(finalState.getName()).isEqualTo("renamed-by-tx1");
    }
}
