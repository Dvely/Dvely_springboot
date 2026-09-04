package com.example.dvely.project.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.FrontendHostingType;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * I45 (#45) review follow-up F6: {@link ProjectEntity#updateFrom} must never touch the
 * Hibernate-managed {@code version} field (see that field's own comment for why). The mocked-
 * entity {@code ProjectRepositoryAdapterTest} stubs {@code updateFrom(...)} entirely rather than
 * running the real implementation, so it can't prove this invariant — this test exercises the
 * actual method body directly.
 */
class ProjectEntityTest {

    @Test
    void updateFromNeverChangesTheManagedVersionField() throws Exception {
        ProjectEntity entity = ProjectEntity.from(newProject());
        // Simulate an entity that Hibernate loaded from a row already at version 5 — from()
        // itself only models a not-yet-persisted insert (version always starts at 0), so this
        // reflection is the only way to put the entity in the "already persisted, mid-lifecycle"
        // state updateFrom() actually runs against in production.
        setVersion(entity, 5L);

        entity.updateFrom(renamedProject());

        assertThat(entity.getVersion()).isEqualTo(5L);
        // Sanity check that updateFrom() did apply the other field changes it's supposed to —
        // otherwise an updateFrom() that touched nothing at all would trivially "pass" this test.
        assertThat(entity.getName()).isEqualTo("renamed");
    }

    @Test
    void frontendHostingTypeDefaultsToGithubPagesAndRoundTripsThroughTheEntity() {
        // 미지정 프로젝트는 기존 동작(GITHUB_PAGES)으로 떨어진다.
        ProjectEntity fresh = ProjectEntity.from(newProject());
        assertThat(fresh.toDomain().getFrontendHostingType()).isEqualTo(FrontendHostingType.GITHUB_PAGES);

        // 명시값은 도메인→엔티티(from)→도메인(toDomain) 왕복에서 보존된다.
        Project s3Project = newProject();
        s3Project.changeFrontendHosting(FrontendHostingType.S3);
        assertThat(ProjectEntity.from(s3Project).toDomain().getFrontendHostingType())
                .isEqualTo(FrontendHostingType.S3);

        // updateFrom 도 값을 반영한다.
        ProjectEntity entity = ProjectEntity.from(newProject());
        Project ec2Project = renamedProject();
        ec2Project.changeFrontendHosting(FrontendHostingType.EC2);
        entity.updateFrom(ec2Project);
        assertThat(entity.toDomain().getFrontendHostingType()).isEqualTo(FrontendHostingType.EC2);
    }

    private void setVersion(ProjectEntity entity, Long version) throws Exception {
        Field field = ProjectEntity.class.getDeclaredField("version");
        field.setAccessible(true);
        field.set(entity, version);
    }

    private Project newProject() {
        return new Project(1L, "my-project", "blank", null, "fast", RepositoryVisibility.PRIVATE);
    }

    private Project renamedProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, "renamed", ProjectStatus.ACTIVE, "blank", null, "fast",
                DeployStatus.DRAFT, null, null, null, null,
                RepositoryVisibility.PRIVATE, RepositoryBindingStatus.NOT_BOUND, RepositoryHealthStatus.UNKNOWN_ERROR,
                false, now, now
        );
    }
}
