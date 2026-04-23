package com.example.dvely.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"status", "code", "message", "data"})
public class ApiResponse<T> {

    private final int status;
    private final String code;
    private final String message;
    private final T data;

    private ApiResponse(int status, String code, String message, T data) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "SUCCESS", "요청이 성공적으로 처리되었습니다", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, "SUCCESS", message, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(200, "SUCCESS", "요청이 성공적으로 처리되었습니다", null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getStatus(), errorCode.getCode(), message, null);
    }
}
