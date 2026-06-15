package com.example.dvely.project.domain.exception;

import com.example.dvely.common.exception.NotFoundException;

public class ProjectNotFoundException extends NotFoundException {

    public ProjectNotFoundException(Long projectId, Long ownerUserId) {
        super("Project not found. projectId=" + projectId + ", ownerUserId=" + ownerUserId);
    }
}
