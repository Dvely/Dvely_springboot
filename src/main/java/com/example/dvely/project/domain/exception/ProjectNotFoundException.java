package com.example.dvely.project.domain.exception;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(Long projectId, Long ownerUserId) {
        super("Project not found. projectId=" + projectId + ", ownerUserId=" + ownerUserId);
    }
}