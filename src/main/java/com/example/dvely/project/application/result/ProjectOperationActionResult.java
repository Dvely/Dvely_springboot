package com.example.dvely.project.application.result;

public record ProjectOperationActionResult(
        String type,
        boolean available,
        String reason
) {
}
