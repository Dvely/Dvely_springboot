package com.example.dvely.apitoken.presentation;

import com.example.dvely.apitoken.application.facade.ApiTokenFacade;
import com.example.dvely.apitoken.application.result.ApiTokenResult;
import com.example.dvely.apitoken.application.result.IssuedApiTokenResult;
import com.example.dvely.apitoken.presentation.dto.request.IssueApiTokenRequest;
import com.example.dvely.apitoken.presentation.dto.response.ApiTokenResponse;
import com.example.dvely.apitoken.presentation.dto.response.IssuedApiTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "ApiToken", description = """
        에이전트·CLI 용 개인 액세스 토큰(PAT) 관리. 브라우저 JWT 는 1시간이라 헤드리스 클라이언트가
        쓸 수 없어 이 토큰을 씁니다. `Authorization: Bearer qp_...` 형태로 기존 API 를 호출합니다.
        평문은 발급 응답에서만 볼 수 있습니다.
        """)
@RestController
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenFacade facade;

    @Operation(summary = "토큰 목록 조회",
            description = "본인이 발급한 토큰만 반환합니다. 평문은 포함되지 않고 앞부분만 표시됩니다.")
    @GetMapping("/api/v1/api-tokens")
    public List<ApiTokenResponse> list(@AuthenticationPrincipal Long userId) {
        return facade.list(userId).stream().map(ApiTokenController::toResponse).toList();
    }

    @Operation(summary = "토큰 발급",
            description = """
                    평문 토큰을 이 응답에서 **한 번만** 반환합니다. 서버는 해시만 보관하므로 이후
                    재조회가 불가능하고, 잃어버리면 새로 발급해야 합니다.
                    READ 토큰으로는 GET 만 가능하며 변경 메서드는 403 입니다.
                    """)
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/api-tokens")
    public IssuedApiTokenResponse issue(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody IssueApiTokenRequest request
    ) {
        IssuedApiTokenResult issued =
                facade.issue(userId, request.scope(), request.label(), request.expiresInDays());
        return new IssuedApiTokenResponse(issued.plaintext(), toResponse(issued.token()));
    }

    @Operation(summary = "토큰 폐기", description = "즉시 무효화됩니다. 발급한 적 없는 ID 면 404 입니다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/api-tokens/{apiTokenId}")
    public void revoke(@AuthenticationPrincipal Long userId, @PathVariable Long apiTokenId) {
        facade.revoke(userId, apiTokenId);
    }

    private static ApiTokenResponse toResponse(ApiTokenResult r) {
        return new ApiTokenResponse(r.apiTokenId(), r.tokenPrefix(), r.scope(), r.label(),
                r.expiresAt(), r.lastUsedAt(), r.createdAt());
    }
}
