package com.example.dvely.auth.presentation;

import com.example.dvely.auth.application.facade.AuthFacade;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.infrastructure.config.FrontendProperties;
import com.example.dvely.auth.presentation.dto.AuthTokenResponse;
import com.example.dvely.auth.presentation.dto.GithubUrlResponse;
import com.example.dvely.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Tag(name = "Auth", description = "GitHub OAuth 및 GitHub App 연동을 통한 인증 API. 로그인, 토큰 갱신, 로그아웃 흐름을 제공합니다.")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;
    private final TokenPort tokenPort;
    private final FrontendProperties frontendProperties;

    // ── OAuth App ──────────────────────────────────────────────────────────────

    @Operation(
            summary = "GitHub OAuth 로그인 URL 조회",
            description = "GitHub OAuth 로그인 페이지 URL을 반환합니다. " +
                          "프론트엔드에서 이 URL로 리다이렉트하면 유저가 GitHub에서 로그인 후 callback으로 code가 전달됩니다. " +
                          "로그인 버튼 클릭 시 호출합니다."
    )
    @GetMapping("/github/url")
    public ApiResponse<GithubUrlResponse> getGithubLoginUrl() {
        return ApiResponse.success(new GithubUrlResponse(authFacade.getGithubLoginUrl().url()));
    }

    @Operation(
            summary = "GitHub OAuth 콜백",
            description = "GitHub로부터 전달된 code를 받아 서비스 JWT와 Refresh Token을 발급합니다. " +
                          "응답의 githubAppInstalled가 false이면 GitHub App 설치가 필요하므로 " +
                          "/api/v1/auth/github/app/install-url로 유저를 유도해야 합니다. " +
                          "GitHub OAuth 인증 후 리다이렉트되는 URL에 연결합니다."
    )
    @GetMapping("/github/callback")
    public ApiResponse<AuthTokenResponse> githubCallback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        var result = authFacade.loginWithGithub(code, state);
        return ApiResponse.success(new AuthTokenResponse(result.accessToken(), result.refreshToken(), result.githubAppInstalled()));
    }

    // ── GitHub App ─────────────────────────────────────────────────────────────

    @Operation(
            summary = "GitHub App 설치 URL 조회",
            description = "GitHub App 설치 페이지 URL을 반환합니다. " +
                          "JWT를 state 파라미터에 담아 URL을 생성하며, GitHub이 설치 완료 후 state를 그대로 콜백으로 돌려줘 " +
                          "별도 세션 없이 설치한 유저를 식별합니다. " +
                          "githubAppInstalled가 false인 유저에게 GitHub App 연동을 유도할 때 호출합니다."
    )
    @GetMapping("/github/app/install-url")
    public ApiResponse<GithubUrlResponse> getGithubAppInstallUrl(
            @Parameter(hidden = true) @RequestHeader("Authorization") String authorization
    ) {
        String token = extractBearerToken(authorization);
        tokenPort.getUserId(token);
        return ApiResponse.success(new GithubUrlResponse(authFacade.getGithubAppInstallUrl(token)));
    }

    @Operation(
            summary = "GitHub App User Token 재인증 URL 조회",
            description = "App 재설치 없이 만료된 GitHub App User Token만 재발급받는 URL을 반환합니다. " +
                          "githubAppInstalled는 true지만 githubAppTokenLinked가 false인 유저에게 사용합니다. " +
                          "이 URL로 이동하면 기존 설치 권한은 유지된 채로 User Token만 새로 발급되어 콜백으로 돌아옵니다."
    )
    @GetMapping("/github/app/reauthorize-url")
    public ApiResponse<GithubUrlResponse> getGithubAppReauthorizeUrl(
            @Parameter(hidden = true) @RequestHeader("Authorization") String authorization
    ) {
        String token = extractBearerToken(authorization);
        Long userId = tokenPort.getUserId(token);
        return ApiResponse.success(new GithubUrlResponse(authFacade.getGithubAppReauthorizeUrl(userId, token)));
    }

    @Operation(
            summary = "GitHub App 설치 완료 콜백",
            description = "GitHub App 설치 후 GitHub이 브라우저를 이 URL로 리다이렉트합니다. " +
                          "state(설치 전 발급한 서비스 JWT)로 유저를 식별하고 Installation ID를 DB에 저장합니다. " +
                          "state가 없는 경우(GitHub App settings 경유 설치)에는 code로 GitHub 유저를 조회해 연동합니다. " +
                          "처리 완료 후 프론트엔드로 리다이렉트합니다. 직접 호출하는 API가 아니라 GitHub이 호출합니다."
    )
    @GetMapping("/github/app/callback")
    public ResponseEntity<Void> githubAppCallback(
            @Parameter(description = "GitHub이 발급한 App Installation ID. 재인증 콜백에서는 없을 수 있음") @RequestParam(value = "installation_id", required = false) Long installationId,
            @Parameter(description = "install | update | delete") @RequestParam(value = "setup_action", defaultValue = "install") String setupAction,
            @Parameter(description = "설치 요청 시 포함했던 서비스 JWT (settings 경유 시 없을 수 있음)") @RequestParam(value = "state", required = false) String state,
            @Parameter(hidden = true) @RequestParam(value = "code", required = false) String code
    ) {
        String baseUrl = frontendProperties.redirectUrl();
        try {
            if (!"delete".equals(setupAction)) {
                if (state != null) {
                    Long userId = tokenPort.getUserId(state);
                    authFacade.linkGithubApp(userId, installationId, code);
                } else if (installationId != null) {
                    authFacade.linkGithubAppByCode(installationId, code);
                } else {
                    throw new IllegalArgumentException("installation_id와 state가 모두 없어 유저를 식별할 수 없습니다.");
                }
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(baseUrl + "?githubAppLinked=true"))
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(baseUrl + "?githubAppLinked=false&error=" + e.getMessage()))
                    .build();
        }
    }

    @Operation(
            summary = "Access Token 재발급",
            description = "만료된 Access Token을 Refresh Token으로 교체합니다. " +
                          "Token Rotation 방식으로 동작하며, 새 Access Token과 새 Refresh Token을 함께 반환합니다. " +
                          "API 호출 중 401 응답을 받으면 이 엔드포인트로 토큰을 재발급한 뒤 요청을 재시도합니다."
    )
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(@RequestBody RefreshRequest request) {
        var result = authFacade.refresh(request.refreshToken());
        return ApiResponse.success(new AuthTokenResponse(result.accessToken(), result.refreshToken(), result.githubAppInstalled()));
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 Access Token을 블랙리스트에 등록해 즉시 무효화하고, 해당 유저의 모든 Refresh Token을 폐기합니다. " +
                          "로그아웃 버튼 클릭 시 호출하며, 이후 클라이언트에서 저장된 토큰을 삭제해야 합니다."
    )
    @DeleteMapping("/logout")
    public ApiResponse<Void> logout(@Parameter(hidden = true) @RequestHeader("Authorization") String authorization) {
        String token = extractBearerToken(authorization);
        Long userId = tokenPort.getUserId(token);
        authFacade.logout(userId, token);
        return ApiResponse.success();
    }

    public record RefreshRequest(String refreshToken) {}

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization 헤더가 올바르지 않습니다");
        }
        return authorization.substring(7);
    }
}
