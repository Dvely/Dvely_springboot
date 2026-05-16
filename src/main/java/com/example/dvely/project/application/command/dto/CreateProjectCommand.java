package com.example.dvely.project.application.command.dto;

public record CreateProjectCommand(
        String name,
        String startMode,
        String templateType,
        String draftMode
) {
}
