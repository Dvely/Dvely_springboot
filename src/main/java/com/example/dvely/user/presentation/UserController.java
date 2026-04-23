package com.example.dvely.user.presentation;

import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.user.application.query.UserQueryService;
import com.example.dvely.user.presentation.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(UserResponse.from(userQueryService.getUser(userId)));
    }
}
