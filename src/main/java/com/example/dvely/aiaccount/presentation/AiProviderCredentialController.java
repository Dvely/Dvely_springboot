package com.example.dvely.aiaccount.presentation;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.facade.AiProviderCredentialFacade;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import com.example.dvely.aiaccount.presentation.dto.request.RegisterAiProviderCredentialRequest;
import com.example.dvely.aiaccount.presentation.dto.response.AiProviderCredentialResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AiCredential", description = """
        본인 AI 제공자 API 키(BYOK) 관리 API. 등록한 키로 Claude Code / Codex 코딩 에이전트가 실행되며
        사용량은 사용자 계정으로 직접 청구됩니다. 평문 키는 어떤 응답에도 포함되지 않습니다.
        """)
@RestController
@RequiredArgsConstructor
public class AiProviderCredentialController {

    private final AiProviderCredentialFacade facade;

    @Operation(
            summary = "등록된 API 키 목록 조회",
            description = "본인이 등록한 키만 반환합니다. 키는 앞 6자만 남긴 마스킹 형태입니다."
    )
    @GetMapping("/api/v1/ai-credentials")
    public List<AiProviderCredentialResponse> list(@AuthenticationPrincipal Long userId) {
        return facade.list(userId).stream().map(AiProviderCredentialController::toResponse).toList();
    }

    @Operation(
            summary = "API 키 등록/교체",
            description = """
                    벤더당 키 하나이므로 등록과 교체가 같은 동작입니다(PUT). 이미 등록돼 있으면 교체됩니다.
                    provider 는 벤더만 받습니다 — CLAUDE_CODE 는 ANTHROPIC 키를, CODEX 는 OPENAI 키를
                    사용하므로 실행 모드로는 등록할 수 없고 400 을 반환합니다.
                    """
    )
    @PutMapping("/api/v1/ai-credentials/{provider}")
    public AiProviderCredentialResponse register(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "벤더", schema = @Schema(allowableValues = {"ANTHROPIC", "OPENAI", "GLM"}))
            @PathVariable AiProvider provider,
            @Valid @RequestBody RegisterAiProviderCredentialRequest request
    ) {
        return toResponse(facade.register(userId, provider, request.apiKey(), request.label()));
    }

    @Operation(
            summary = "API 키 삭제",
            description = "등록돼 있지 않으면 404 를 반환합니다."
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/ai-credentials/{provider}")
    public void delete(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "벤더", schema = @Schema(allowableValues = {"ANTHROPIC", "OPENAI", "GLM"}))
            @PathVariable AiProvider provider
    ) {
        facade.delete(userId, provider);
    }

    private static AiProviderCredentialResponse toResponse(AiProviderCredentialResult result) {
        return new AiProviderCredentialResponse(
                result.aiProviderCredentialId(),
                result.provider(),
                result.maskedApiKey(),
                result.label(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
