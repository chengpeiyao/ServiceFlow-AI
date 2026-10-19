package com.serviceflow.shared;

import cn.dev33.satoken.exception.NotLoginException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorBody(String code, String message, String requestId) {}

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorBody> business(BusinessException error) {
        return response(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<ErrorBody> login(NotLoginException error) {
        return response(401, "UNAUTHENTICATED", "请先登录");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException error) {
        return response(400, "REQUEST_INVALID", "请求字段缺失、格式错误或超出长度限制");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> json(HttpMessageNotReadableException error, HttpServletRequest request) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof UnrecognizedPropertyException && request.getRequestURI().endsWith("/triage")) {
                return response(422, "TRIAGE_FIELD_NOT_EDITABLE", "受理请求含有不允许修改的字段");
            }
            cause = cause.getCause();
        }
        return response(400, "REQUEST_INVALID", "请求 JSON 格式或字段不正确");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception error) {
        String requestId = UUID.randomUUID().toString();
        log.error("请求执行失败，requestId={}", requestId, error);
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "服务执行失败，请稍后重试", requestId));
    }

    private ResponseEntity<ErrorBody> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message, UUID.randomUUID().toString()));
    }
}
