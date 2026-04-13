package com.example.dvely.auth.presentation;

import com.example.dvely.auth.application.facade.AuthFacade;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.presentation.dto.AuthTokenResponse;
import com.example.dvely.auth.presentation.dto.GithubUrlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    public GithubUrlResponse getGithubLoginUrl() {
        return new GithubUrlResponse(authFacade.getGithubLoginUrl());
    }

    /**
     * GitHub OAuth 콜백
     * 응답의 githubAppInstalled=false면 /github/app/install-url을 유저에게 제공
     */
    @GetMapping("/github/callback")
    public AuthTokenResponse githubCallback(@RequestParam String code) {
        var result = authFacade.loginWithGithub(code);
        return new AuthTokenResponse(result.accessToken(), result.githubAppInstalled());
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
    public GithubUrlResponse getGithubAppInstallUrl(
            @RequestHeader("Authorization") String authorization
    ) {
        String token = extractBearerToken(authorization);
        // JWT 유효성 검증 (만료/변조 시 여기서 예외)
        tokenPort.getUserId(token);
        return new GithubUrlResponse(authFacade.getGithubAppInstallUrl(token));
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
    public ResponseEntity<Void> githubAppCallback(
            @RequestParam("installation_id") Long installationId,
            @RequestParam(value = "setup_action", defaultValue = "install") String setupAction,
            @RequestParam("state") String state
    ) {
        if ("delete".equals(setupAction)) {
            return ResponseEntity.ok().build();
        }

        // state(JWT) 검증 → userId 추출 → installation 연결
        Long userId = tokenPort.getUserId(state);
        authFacade.linkGithubApp(userId, installationId);

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/logout")
    public ResponseEntity<Void> logout() {
        // TODO: JWT 블랙리스트 처리 (Redis 등)
        return ResponseEntity.ok().build();
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization 헤더가 올바르지 않습니다");
        }
        return authorization.substring(7);
    }
}
