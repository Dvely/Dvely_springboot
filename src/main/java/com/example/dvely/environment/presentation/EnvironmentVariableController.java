package com.example.dvely.environment.presentation;

import com.example.dvely.environment.application.facade.EnvironmentVariableFacade;
import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.presentation.dto.request.CreateEnvironmentVariableRequest;
import com.example.dvely.environment.presentation.dto.request.UpdateEnvironmentVariableRequest;
import com.example.dvely.environment.presentation.dto.response.EnvironmentVariableHistoryResponse;
import com.example.dvely.environment.presentation.dto.response.EnvironmentVariableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Environment", description = "프로젝트별 환경변수/Secrets 관리 API. secret 값은 어떤 응답에도 포함되지 않습니다.")
@RestController
@RequiredArgsConstructor
public class EnvironmentVariableController {

    private final EnvironmentVariableFacade environmentVariableFacade;

    @Operation(
            summary = "환경변수 목록 조회",
            description = "scope 쿼리 파라미터로 필터링(생략 시 전체, scope asc → key asc 정렬). " +
                          "secret 값은 응답에 포함되지 않습니다(value=null)."
    )
    @GetMapping("/api/v1/projects/{projectId}/environment-variables")
    public List<EnvironmentVariableResponse> getVariables(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestParam(required = false) String scope
    ) {
        return environmentVariableFacade.getVariables(userId, projectId, scope).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(
            summary = "환경변수 변경 이력 조회",
            description = "limit 기본 50, 최대 200(초과 시 200으로 보정). 최신순(생성시각 desc). " +
                          "값 자체는 기록하지 않으므로 응답에도 포함되지 않습니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/environment-variables/history")
    public List<EnvironmentVariableHistoryResponse> getHistory(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestParam(required = false) Integer limit
    ) {
        return environmentVariableFacade.getHistory(userId, projectId, limit).stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    @Operation(
            summary = "환경변수 생성",
            description = "동일 (프로젝트, scope, key) 조합이 이미 있으면 409를 반환합니다. " +
                          "secret=true로 생성해도 이 응답의 value는 null입니다."
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/projects/{projectId}/environment-variables")
    public EnvironmentVariableResponse create(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @Valid @RequestBody CreateEnvironmentVariableRequest request
    ) {
        return toResponse(environmentVariableFacade.create(
                userId, projectId, request.scope(), request.key(), request.value(), request.secret()
        ));
    }

    @Operation(
            summary = "환경변수 수정",
            description = "value/secret만 수정할 수 있습니다(key·scope는 불변, 이름 변경은 삭제 후 재생성). " +
                          "secret은 true→false로 되돌릴 수 없습니다(400)."
    )
    @PatchMapping("/api/v1/projects/{projectId}/environment-variables/{variableId}")
    public EnvironmentVariableResponse update(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @PathVariable Long variableId,
            @Valid @RequestBody UpdateEnvironmentVariableRequest request
    ) {
        return toResponse(environmentVariableFacade.update(
                userId, projectId, variableId, request.value(), request.secret()
        ));
    }

    @Operation(summary = "환경변수 삭제", description = "존재하지 않는 변수를 삭제하려 하면 404를 반환합니다(멱등 아님).")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/projects/{projectId}/environment-variables/{variableId}")
    public void delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @PathVariable Long variableId
    ) {
        environmentVariableFacade.delete(userId, projectId, variableId);
    }

    private EnvironmentVariableResponse toResponse(EnvironmentVariableResult result) {
        return new EnvironmentVariableResponse(
                result.environmentVariableId(),
                result.scope(),
                result.key(),
                result.value(),
                result.secret(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    private EnvironmentVariableHistoryResponse toHistoryResponse(EnvironmentVariableHistoryResult result) {
        return new EnvironmentVariableHistoryResponse(
                result.historyId(),
                result.environmentVariableId(),
                result.scope(),
                result.key(),
                result.action(),
                result.secret(),
                result.valueChanged(),
                result.actorUserId(),
                result.createdAt()
        );
    }
}
