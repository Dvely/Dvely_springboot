package com.example.dvely.provisioning.presentation.dto.request;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "DB 프로비저닝 요청")
public record CreateDatabaseRequest(
        @Schema(description = "DB 마련 방식", allowableValues = {"LOCAL", "RDS", "DOCKER"}, example = "LOCAL")
        @NotNull ProvisionMethod method,

        @Schema(description = "DB 엔진", allowableValues = {"POSTGRESQL", "MYSQL"}, example = "POSTGRESQL")
        @NotNull DatabaseEngine engine
) {}
