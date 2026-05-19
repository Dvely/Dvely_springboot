package com.example.dvely.agent.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record TaskInputRequest(@NotBlank String value) {}
