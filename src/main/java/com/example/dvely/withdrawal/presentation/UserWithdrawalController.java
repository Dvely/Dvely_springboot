package com.example.dvely.withdrawal.presentation;

import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.common.response.ErrorCode;
import com.example.dvely.withdrawal.application.UserWithdrawalFacade;
import com.example.dvely.withdrawal.application.WithdrawalBlockedException;
import com.example.dvely.withdrawal.presentation.dto.LiveResourceResponse;
import com.example.dvely.withdrawal.presentation.dto.WithdrawalCheckResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 탈퇴(#613). {@code /api/v1/users} 아래에 있지만 사용자 조회와 섞지 않으려고 따로 둔다.
 *
 * <p><b>브라우저 로그인으로만 탈퇴한다.</b> 개인 액세스 토큰(PAT)의 쓰기는 {@code JwtAuthenticationFilter} 의 허용 목록에 있는
 * 것만 통과하는데 이 경로는 거기에 없다 — 에이전트가 사용자의 계정을 지울 이유가 없다. 목록이 언젠가 넓게 잘못 고쳐져도 여기서
 * 한 번 더 막는다.</p>
 */
@Tag(name = "User", description = "로그인한 유저 정보 API.")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserWithdrawalController {

    private final UserWithdrawalFacade withdrawalFacade;
    private final TokenPort tokenPort;

    @Operation(
            summary = "탈퇴 가능 여부 조회",
            description = "탈퇴를 막는 자원(사용자 AWS 계정에 아직 살아 있는 서버·DB·CloudFront, 정리 중인 CloudFront·인증서, "
                    + "대기·진행 중인 배포)이 있는지 돌려줍니다. 탈퇴 화면을 열 때 호출해, 먼저 정리할 것을 보여 줍니다. "
                    + "이미 삭제한 프로젝트의 자원도 포함됩니다."
    )
    @GetMapping("/me/withdrawal")
    public ApiResponse<WithdrawalCheckResponse> checkWithdrawal(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(WithdrawalCheckResponse.of(
                LiveResourceResponse.from(withdrawalFacade.findLiveResources(userId))));
    }

    @Operation(
            summary = "회원 탈퇴",
            description = "내 계정과 프로젝트·대화·실행 기록·클라우드 연결·AI 키·액세스 토큰 등 Qeploy 에 저장된 내 데이터를 모두 지웁니다. "
                    + "진행 중인 에이전트 작업은 취소되고 프리뷰는 닫히며, 지금 쓰는 로그인 토큰과 모든 리프레시 토큰이 폐기됩니다. "
                    + "GitHub 저장소와 AWS 계정의 자원은 사용자 소유라 지우지 않습니다. GitHub App 설치는 GitHub 에서 직접 해제해야 합니다. "
                    + "AWS 에 살아 있는 자원이 있으면 아무것도 지우지 않고 409(WITHDRAWAL_BLOCKED)와 함께 그 목록을 돌려줍니다. "
                    + "브라우저 로그인으로만 호출할 수 있습니다(개인 액세스 토큰은 403). 이미 탈퇴한 뒤 다시 호출해도 성공으로 응답합니다."
    )
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Long userId,
                                      @Parameter(hidden = true) @RequestHeader("Authorization") String authorization) {
        String token = bearerToken(authorization);
        if (ApiTokenGenerator.looksLikeApiToken(token)) {
            throw new ForbiddenException("탈퇴는 개인 액세스 토큰으로 할 수 없습니다. 웹에서 진행해주세요.");
        }
        withdrawalFacade.withdraw(userId, tokenPort.parseClaims(token));
        return ApiResponse.success();
    }

    /** 409 에 남은 자원 목록을 싣는다. 이 컨트롤러에서 던진 것만 받는다. */
    @ExceptionHandler(WithdrawalBlockedException.class)
    public ResponseEntity<ApiResponse<WithdrawalCheckResponse>> handleBlocked(WithdrawalBlockedException e) {
        ErrorCode code = ErrorCode.WITHDRAWAL_BLOCKED;
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(),
                        WithdrawalCheckResponse.of(LiveResourceResponse.from(e.liveResources()))));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization 헤더가 올바르지 않습니다");
        }
        return authorization.substring(7);
    }
}
