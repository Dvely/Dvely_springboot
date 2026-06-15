package com.example.dvely.project.domain.service;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ProjectDomainService {

    public Project create(Long ownerUserId,
                          String name,
                          String startMode,
                          String templateType,
                          String draftMode,
                          RepositoryVisibility visibility) {
        String normalizedStartMode = normalizeStartMode(startMode);
        String normalizedTemplateType = normalizeTemplateType(normalizedStartMode, templateType);
        return new Project(
                ownerUserId,
                name,
                normalizedStartMode,
                normalizedTemplateType,
                normalizeDraftMode(draftMode),
                visibility
        );
    }

    public void rename(Project project, String name) {
        project.rename(name);
    }

    public void delete(Project project) {
        project.softDelete();
    }

    private String normalizeStartMode(String startMode) {
        String value = requireText(startMode, "startMode").toLowerCase(Locale.ROOT);
        if (!"blank".equals(value) && !"template".equals(value)) {
            throw new IllegalArgumentException("startMode must be blank or template");
        }
        return value;
    }

    private String normalizeTemplateType(String startMode, String templateType) {
        if ("blank".equals(startMode)) {
            return null;
        }
        String value = requireText(templateType, "templateType")
                .toLowerCase(Locale.ROOT)
                .replace(' ', '-');
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,49}")) {
            throw new IllegalArgumentException(
                    "templateType must use letters, numbers, hyphen, or underscore"
            );
        }
        return value;
    }

    private String normalizeDraftMode(String draftMode) {
        String value = draftMode == null || draftMode.isBlank()
                ? "fast"
                : draftMode.trim().toLowerCase(Locale.ROOT);
        if (!"fast".equals(value) && !"quality".equals(value)) {
            throw new IllegalArgumentException("draftMode must be fast or quality");
        }
        return value;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
