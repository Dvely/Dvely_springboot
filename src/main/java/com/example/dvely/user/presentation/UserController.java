package com.example.dvely.user.presentation;

import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.user.application.query.UserQueryService;
import com.example.dvely.user.presentation.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "로그인한 유저 정보 API.")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;

    @Operation(
            summary = "내 정보 조회",
            description = "JWT로 인증된 현재 로그인 유저의 프로필 정보를 반환합니다. " +
                          "헤더, 사이드바 등 로그인 상태 표시 및 GitHub App 토큰 상태 확인에 사용합니다. " +
                          "githubAppRefreshTokenExpiresAt이 현재 시각보다 과거이면 GitHub App 재설치가 필요합니다."
    )
    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(UserResponse.from(userQueryService.getUser(userId)));
    }
}
