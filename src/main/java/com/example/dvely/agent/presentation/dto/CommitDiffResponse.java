package com.example.dvely.agent.presentation.dto;

import java.util.List;

public record CommitDiffResponse(
        String        changeId,
        DiffStatsDto  stats,
        List<FileDiffDto> files
) {}
