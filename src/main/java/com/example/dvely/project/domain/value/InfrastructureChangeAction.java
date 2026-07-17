package com.example.dvely.project.domain.value;

/**
 * Whether an infrastructure setting change row is the project's first configuration
 * (CREATED — determined by whether {@code project_infrastructure_settings} already has a row,
 * not by history existence, so a project with only REJECTED changes still counts as CREATED on
 * its next request) or a change to an already-applied configuration (UPDATED).
 */
public enum InfrastructureChangeAction {
    CREATED,
    UPDATED
}
