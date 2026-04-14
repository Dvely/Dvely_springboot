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
        return new Project(ownerUserId, name, normalizeStartMode(startMode), templateType, draftMode, visibility);
    }

    public void rename(Project project, String name) {
        project.rename(name);
    }

    public void delete(Project project) {
        project.softDelete();
    }

    public void bindRepository(Project project,
                               String bindingType,
                               String repositoryFullName,
                               String repositoryName,
                               String githubLogin,
                               RepositoryVisibility visibility) {
        String normalizedType = normalizeBindingType(bindingType);

        if ("existing".equals(normalizedType)) {
            project.bindRepository(repositoryFullName, repositoryFullName, visibility);
            return;
        }

        String owner = (githubLogin == null || githubLogin.isBlank()) ? "me" : githubLogin.trim();
        String fullName = owner + "/" + requireText(repositoryName, "repositoryName");
        project.bindRepository(fullName, fullName, visibility);
    }

    public void updateRepositoryBinding(Project project,
                                        String deploymentRepository,
                                        RepositoryVisibility visibility) {
        project.updateRepositoryBinding(deploymentRepository, visibility);
    }

    private String normalizeStartMode(String startMode) {
        String value = requireText(startMode, "startMode").toLowerCase(Locale.ROOT);
        if (!"blank".equals(value) && !"template".equals(value)) {
            throw new IllegalArgumentException("startMode must be blank or template");
        }
        return value;
    }

    private String normalizeBindingType(String bindingType) {
        String value = requireText(bindingType, "bindingType").toLowerCase(Locale.ROOT);
        if (!"existing".equals(value) && !"create".equals(value)) {
            throw new IllegalArgumentException("bindingType must be existing or create");
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
