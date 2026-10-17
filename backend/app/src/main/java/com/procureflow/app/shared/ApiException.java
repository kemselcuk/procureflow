package com.procureflow.app.shared;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "forbidden"); }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "not_found"); }
    public static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code); }
    public static ApiException invalid(String code) { return new ApiException(HttpStatus.BAD_REQUEST, code); }
}
