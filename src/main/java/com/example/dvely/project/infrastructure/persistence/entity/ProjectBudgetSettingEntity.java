package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.ProjectBudgetSetting;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA row for {@code project_budget_settings} (V27 migration) — the project's monthly budget,
 * one row per project (project_id is both PK and FK, no surrogate id, mirroring
 * {@code ProjectInfrastructureSettingEntity}). Only the budget amount/currency are stored; the
 * cost estimate it is compared against is always recomputed on read (design D2), never persisted
 * here.
 */
@Entity
@Table(name = "project_budget_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectBudgetSettingEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "monthly_budget_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyBudgetAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private ProjectBudgetSettingEntity(ProjectBudgetSetting setting) {
        this.projectId = setting.getProjectId();
        applyValues(setting);
    }

    public static ProjectBudgetSettingEntity from(ProjectBudgetSetting setting) {
        return new ProjectBudgetSettingEntity(setting);
    }

    public void updateFrom(ProjectBudgetSetting setting) {
        applyValues(setting);
    }

    private void applyValues(ProjectBudgetSetting setting) {
        this.monthlyBudgetAmount = setting.getMonthlyBudgetAmount();
        this.currency = setting.getCurrency();
    }

    public ProjectBudgetSetting toDomain() {
        return new ProjectBudgetSetting(projectId, monthlyBudgetAmount, currency, createdAt, updatedAt);
    }
}
