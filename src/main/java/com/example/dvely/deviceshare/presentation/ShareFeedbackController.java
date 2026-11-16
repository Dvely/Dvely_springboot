package com.example.dvely.deviceshare.presentation;

import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.common.response.ErrorCode;
import com.example.dvely.common.response.RawApiResponse;
import com.example.dvely.deviceshare.application.service.ShareFeedbackService;
import com.example.dvely.deviceshare.application.service.ShareFeedbackTransitionException;
import com.example.dvely.deviceshare.application.service.ShareFeedbackView;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import com.example.dvely.deviceshare.presentation.dto.MarkShareFeedbackFixedRequest;
import com.example.dvely.deviceshare.presentation.dto.ShareFeedbackDetailResponse;
import com.example.dvely.deviceshare.presentation.dto.ShareFeedbackPromptResponse;
import com.example.dvely.deviceshare.presentation.dto.ShareFeedbackResponse;
import com.example.dvely.deviceshare.presentation.dto.UpdateShareFeedbackStatusRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "DeviceShareFeedback", description = """
        짚기 피드백(#681). 공유한 화면의 한 지점을 짚어 남긴 피드백 — 요소·화면·컴포넌트·소스 후보·이미지와 글.
        손님 글은 주인이 받아야(ACCEPTED) 에이전트로 나간다. 개인 액세스 토큰으로 부르면 받기 전(NEW)·받지 않은(DISMISSED)
        피드백은 목록에 없고 상세·프롬프트·이미지가 404 다. 설계: docs/device-preview-share-design.md §14.
        """)
@Slf4j
@RestController
@RequiredArgsConstructor
public class ShareFeedbackController {

    private final ShareFeedbackService service;
    private final ObjectMapper json;

    @Operation(summary = "공유 하나의 피드백 목록", description = "최근 100개. 개인 액세스 토큰이면 받은 것(ACCEPTED·FIXED·CONFIRMED)만.")
    @GetMapping("/api/v1/device-shares/{shareId}/feedback")
    public List<ShareFeedbackResponse> listForShare(@AuthenticationPrincipal Long userId, @PathVariable Long shareId,
                                                    Authentication auth) {
        return service.listForShare(userId, shareId, isAgent(auth)).stream().map(ShareFeedbackResponse::from).toList();
    }

    @Operation(summary = "내 모든 공유의 피드백", description = """
            status 를 쉼표로 여럿 줄 수 있다(기본 ACCEPTED). 최근에 바뀐 것부터, limit 기본 20·최대 50.
            개인 액세스 토큰이면 NEW·DISMISSED 는 빠진다. MCP 가 "받은 피드백"을 읽는 길이다.
            """)
    @GetMapping("/api/v1/device-share-feedback")
    public List<ShareFeedbackResponse> listForUser(@AuthenticationPrincipal Long userId,
                                                   @RequestParam(name = "status", required = false) List<FeedbackStatus> status,
                                                   @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
                                                   Authentication auth) {
        return service.listForUser(userId, status, limit, isAgent(auth)).stream().map(ShareFeedbackResponse::from).toList();
    }

    @Operation(summary = "피드백 상세", description = "요소 기술(§14.3)과 이어진 대화, 이미지가 있는지.")
    @GetMapping("/api/v1/device-share-feedback/{feedbackId}")
    public ShareFeedbackDetailResponse get(@AuthenticationPrincipal Long userId, @PathVariable Long feedbackId,
                                           Authentication auth) {
        return detail(service.get(userId, feedbackId, isAgent(auth)));
    }

    @Operation(summary = "에이전트용 프롬프트", description = """
            Markdown(설계 §14.5). 사람이 쓴 글은 펜스 블록에 들어 있고 지시가 아니라 데이터라고 적혀 있다. 이미지 줄은 없다.
            """)
    @GetMapping("/api/v1/device-share-feedback/{feedbackId}/prompt")
    public ShareFeedbackPromptResponse prompt(@AuthenticationPrincipal Long userId, @PathVariable Long feedbackId,
                                              Authentication auth) {
        return new ShareFeedbackPromptResponse(service.prompt(userId, feedbackId, isAgent(auth)));
    }

    @Operation(summary = "피드백 이미지", description = "kind=crop(짚은 요소를 잘라 낸 것, 기본) | screen(그 순간의 화면). image/jpeg. 없으면 404.")
    @RawApiResponse
    @GetMapping("/api/v1/device-share-feedback/{feedbackId}/image")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Long userId, @PathVariable Long feedbackId,
                                        @RequestParam(name = "kind", required = false, defaultValue = "crop") String kind,
                                        Authentication auth) {
        if (!"crop".equals(kind) && !"screen".equals(kind)) {
            throw new IllegalArgumentException("kind 는 crop 또는 screen 이어야 합니다.");
        }
        byte[] bytes = service.image(userId, feedbackId, "crop".equals(kind), isAgent(auth));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }

    @Operation(summary = "받기·무시·확인", description = """
            브라우저 로그인만(개인 액세스 토큰으로는 막힌다). status = ACCEPTED | DISMISSED | CONFIRMED.
            설계 §14.2 에 없는 변경은 409 DEVICE_SHARE_FEEDBACK_INVALID_TRANSITION.
            """)
    @PatchMapping("/api/v1/device-share-feedback/{feedbackId}")
    public ShareFeedbackDetailResponse changeStatus(@AuthenticationPrincipal Long userId, @PathVariable Long feedbackId,
                                                    @Valid @RequestBody UpdateShareFeedbackStatusRequest request) {
        return detail(service.changeStatus(userId, feedbackId, request.status()));
    }

    @Operation(summary = "고쳤다고 표시", description = """
            받은(ACCEPTED) 피드백을 FIXED 로. note(300자)는 짚은 사람의 뷰어에 보인다. 개인 액세스 토큰(WRITE)으로도 부를 수 있다
            — MCP 의 qeploy_share_feedback_update 가 이 길이다. 받기 전이면 404, 다른 상태면 409.
            """)
    @PostMapping("/api/v1/device-share-feedback/{feedbackId}/fixed")
    public ShareFeedbackDetailResponse markFixed(@AuthenticationPrincipal Long userId, @PathVariable Long feedbackId,
                                                 @Valid @RequestBody(required = false) MarkShareFeedbackFixedRequest request,
                                                 Authentication auth) {
        return detail(service.markFixed(userId, feedbackId, request == null ? null : request.note(), isAgent(auth)));
    }

    @ExceptionHandler(ShareFeedbackTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransition(ShareFeedbackTransitionException e) {
        log.info("Device share feedback transition refused: {}", e.getMessage());
        ErrorCode code = ErrorCode.DEVICE_SHARE_FEEDBACK_INVALID_TRANSITION;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code, e.getMessage()));
    }

    private ShareFeedbackDetailResponse detail(ShareFeedbackView view) {
        Map<String, Object> element = json.convertValue(view.element(), new TypeReference<>() {
        });
        return ShareFeedbackDetailResponse.from(view, element);
    }

    /** 개인 액세스 토큰은 권한이 {@code SCOPE_READ}·{@code SCOPE_WRITE} 다. 브라우저 로그인은 {@code ROLE_USER}. */
    static boolean isAgent(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.startsWith("SCOPE_"));
    }
}
