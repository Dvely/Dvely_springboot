package com.example.dvely.common.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestControllerAdvice(basePackages = "com.example.dvely")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> responseType = returnType.getParameterType();
        return !returnType.getContainingClass().isAnnotationPresent(RawApiResponse.class)
                && !returnType.hasMethodAnnotation(RawApiResponse.class)
                && !ApiResponse.class.isAssignableFrom(responseType)
                && !SseEmitter.class.isAssignableFrom(responseType)
                && responseType != byte[].class
                && responseType != Void.TYPE
                && responseType != Void.class;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null
                || body instanceof ApiResponse<?>
                || MediaType.TEXT_EVENT_STREAM.includes(selectedContentType)) {
            return body;
        }
        return ApiResponse.success(resolveStatus(response), body);
    }

    private int resolveStatus(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            return servletResponse.getServletResponse().getStatus();
        }
        return 200;
    }
}
