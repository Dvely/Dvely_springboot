package com.example.dvely.agent.presentation.dto;

public record FileDiffDto(
        String filename,
        String status,
        int    additions,
        int    deletions,
        int    changes,
        String patch
) {}
