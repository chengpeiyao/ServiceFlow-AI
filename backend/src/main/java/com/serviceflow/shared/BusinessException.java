package com.serviceflow.shared;

public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    public static BusinessException invalid(String code, String message) {
        return new BusinessException(422, code, message);
    }

    public static BusinessException forbidden() {
        return new BusinessException(404, "RESOURCE_FORBIDDEN", "资源不存在或无权访问");
    }
}
