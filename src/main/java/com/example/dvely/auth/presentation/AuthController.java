package com.example.dvely.auth.presentation;

import com.example.dvely.auth.application.facade.AuthFacade;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.presentation.dto.AuthTokenResponse;
import com.example.dvely.auth.presentation.dto.GithubUrlResponse;
import com.example.dvely.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;
    private final TokenPort tokenPort;

    // ── OAuth App ──────────────────────────────────────────────────────────────

    /**
     * GitHub OAuth 로그인 URL 반환
     * 프론트에서 이 URL로 리다이렉트 → 유저 GitHub 로그인 → callback으로 code 전달
     */
    @GetMapping("/github/url")
    public ApiResponse<GithubUrlResponse> getGithubLoginUrl() {
        return ApiResponse.success(new GithubUrlResponse(authFacade.getGithubLoginUrl()));
    }

    /**
     * GitHub OAuth 콜백
     * 응답의 githubAppInstalled=false면 /github/app/install-url을 유저에게 제공
     */
    @GetMapping("/github/callback")
    public ApiResponse<AuthTokenResponse> githubCallback(@RequestParam String code) {
        var result = authFacade.loginWithGithub(code);
        return ApiResponse.success(new AuthTokenResponse(result.accessToken(), result.refreshToken(), result.githubAppInstalled()));
    }

    // ── GitHub App ─────────────────────────────────────────────────────────────

    /**
     * GitHub App 설치 URL 반환
     *
     * 로그인한 유저의 JWT를 state 파라미터에 포함시켜 URL 생성
     * GitHub가 설치 완료 후 state를 그대로 콜백으로 돌려줌
     * → 별도 세션 없이도 어떤 유저가 설치했는지 식별 가능
     *
     * 반환 예시:
     * https://github.com/apps/{app-slug}/installations/new?state={serviceJwt}
     */
    @GetMapping("/github/app/install-url")
    public ApiResponse<GithubUrlResponse> getGithubAppInstallUrl(
            @RequestHeader("Authorization") String authorization
    ) {
        String token = extractBearerToken(authorization);
        tokenPort.getUserId(token);
        return ApiResponse.success(new GithubUrlResponse(authFacade.getGithubAppInstallUrl(token)));
    }

    /**
     * GitHub App 설치 완료 콜백
     *
     * GitHub이 설치 후 브라우저를 이 URL로 리다이렉트:
     *   /callback?installation_id=xxx&setup_action=install&state={serviceJwt}
     *
     * state(= 설치 전에 넣어둔 서비스 JWT)를 검증해 유저를 식별
     * Authorization 헤더 불필요 - 브라우저 리다이렉트라 헤더를 실을 수 없음
     *
     * @param installationId GitHub이 발급한 App Installation ID
     * @param setupAction    "install" | "update" | "delete"
     * @param state          install-url 요청 시 포함했던 서비스 JWT
     */
    @GetMapping("/github/app/callback")
    public ApiResponse<Void> githubAppCallback(
            @RequestParam("installation_id") Long installationId,
            @RequestParam(value = "setup_action", defaultValue = "install") String setupAction,
            @RequestParam("state") String state
    ) {
        if (!"delete".equals(setupAction)) {
            Long userId = tokenPort.getUserId(state);
            authFacade.linkGithubApp(userId, installationId);
        }
        return ApiResponse.success();
    }

    /**
     * Access Token 재발급
     * Refresh Token을 받아 새 Access Token + 새 Refresh Token 반환 (Token Rotation)
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(@RequestBody RefreshRequest request) {
        var result = authFacade.refresh(request.refreshToken());
        return ApiResponse.success(new AuthTokenResponse(result.accessToken(), result.refreshToken(), result.githubAppInstalled()));
    }

    /**
     * 로그아웃
     * - Access Token 즉시 무효화 (블랙리스트 등록)
     * - 모든 Refresh Token 폐기
     */
    @DeleteMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
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
