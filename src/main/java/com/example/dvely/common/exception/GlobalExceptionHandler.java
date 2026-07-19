package com.example.dvely.common.exception;

import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.common.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400 - 필수 파라미터 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "필수 파라미터가 누락되었습니다: " + e.getParameterName();
        log.warn("Missing parameter: {}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        String message = "필수 헤더가 누락되었습니다: " + e.getHeaderName();
        log.warn("Missing header: {}", e.getHeaderName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, message));
    }

    // 400 - @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다");
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, message));
    }

    // 400 - 요청 본문 파싱 실패 (잘못된 JSON, Content-Type 오류 등)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Message not readable: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "요청 본문을 읽을 수 없습니다. JSON 형식과 Content-Type을 확인해주세요."));
    }

    // 400 - 경로 변수/쿼리 파라미터 타입 불일치 (예: @PathVariable Long projectId 에 "abc" 전달)
    // Spring MVC는 바인딩 단계에서 타입 변환에 실패하면 MethodArgumentTypeMismatchException을 던지는데,
    // 이는 클라이언트가 잘못된 형식의 입력을 보낸 것이지 서버 내부 오류가 아니므로 400으로 응답해야 한다.
    // 이 핸들러가 없으면 최하단 handleException(Exception)으로 흘러 500이 반환되어(클라이언트 입력 오류를
    // 서버 장애로 오인하게 만듦), FE 에러 처리 로직이 재시도 등을 잘못 트리거할 수 있다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String requiredType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "알 수 없음";
        String message = "'%s' 파라미터의 값 '%s'이(가) 올바른 형식(%s)이 아닙니다"
                .formatted(e.getName(), e.getValue(), requiredType);
        // Unlike other handlers above, this value is whatever failed to convert to the target
        // type — for query parameters that can be arbitrary attacker-controlled text (path
        // variables are constrained by servlet URL decoding, but query strings are more
        // permissive). Strip CR/LF before logging so a crafted value (e.g. "1%0d%0a[ERROR] fake")
        // cannot forge extra lines in the plain-text console log.
        log.warn("Type mismatch: parameter={}, value={}, requiredType={}",
                e.getName(), sanitizeForLog(e.getValue()), requiredType);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, message));
    }

    private static String sanitizeForLog(Object value) {
        return String.valueOf(value).replaceAll("[\r\n]", "_");
    }

    // 400 - 잘못된 요청 인자
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    // 401 - 인증 실패
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
    }

    // 403 - 권한 없음
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException e) {
        log.warn("Forbidden: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, e.getMessage()));
    }

    // 404 - 리소스 없음
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    // 409 - 낙관적 잠금 경합 (버전 불일치 또는 수정/삭제 진행 중 대상 행이 동시에 삭제된 경합)
    // (I45, #45) 이 핸들러는 두 가지 원인을 모두 받는다: ① Hibernate @Version이 flush 시점에
    // WHERE version=? 조건의 UPDATE가 0건 영향을 감지(다른 트랜잭션이 그 사이 커밋)한 경우, ②
    // 어댑터 레벨 버전 가드(ProjectRepositoryAdapter 등)가 도메인 객체가 들고 온 version과 현재
    // 행의 version 불일치를 직접 감지해 던진 경우 — 둘 다 "낡은 스냅샷으로 저장을 시도했다"는 같은
    // 의미이므로 동일 예외 타입(D2)과 동일 HTTP 코드로 처리한다.
    // 과거(U3 F5)에는 이 핸들러가 404를 반환했다 — 그 시점엔 @Version이 없어 OOLFE의 사실상 유일한
    // 발생 원인이 "동시 삭제"였기 때문. projects에 @Version이 도입된 뒤로는 OOLFE의 지배적 의미가
    // "행이 존재하며 버전이 경합했다"로 바뀌었으므로, 존재하는 리소스에 404를 응답하는 것은 부정확
    // 하다(D3). 삭제 경합과 버전 경합은 예외 타입만으로는 구분 불가능하므로 409로 통일하고, 클라이
    // 언트가 재조회하면 실제로 삭제된 케이스는 그 재조회 자체가 자연스럽게 404로 수렴한다(왕복 1회
    // 추가). 이 핸들러는 전 도메인 공용이므로 environment 등 타 도메인의 "수정 중 동시 삭제" 응답도
    // 함께 404→409로 바뀐다 — 재조회 수렴 흐름은 동일하게 유지된다.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic locking conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT,
                        "다른 요청과 동시에 수정되어 처리하지 못했습니다. 잠시 후 다시 시도해주세요."));
    }

    // 405 - 허용되지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(
                        ErrorCode.METHOD_NOT_ALLOWED,
                        "허용되지 않는 HTTP 메서드입니다: " + e.getMethod()
                ));
    }

    // 500 - 내부 오류
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("Conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT, e.getMessage()));
    }

    // 500 - 예상치 못한 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
