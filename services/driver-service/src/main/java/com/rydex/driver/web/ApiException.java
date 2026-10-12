package com.rydex.driver.web;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an RFC 7807 response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String title, String detail) {
        this(status, title, detail, null);
    }

    private ApiException(HttpStatus status, String title, String detail, Long retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "Forbidden", detail);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", what + " not found");
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable", detail);
    }

    public static ApiException tooManyRequests(String detail, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", detail, retryAfterSeconds);
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
