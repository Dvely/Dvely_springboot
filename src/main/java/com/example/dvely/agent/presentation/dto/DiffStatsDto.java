package com.example.dvely.agent.presentation.dto;

public record DiffStatsDto(
        int additions,
        int deletions,
        int total
) {}
