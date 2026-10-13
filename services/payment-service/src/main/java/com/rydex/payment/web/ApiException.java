package com.rydex.payment.web;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an RFC 7807 response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
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

    /** The payment provider could not be reached or answered with an error. */
    public static ApiException badGateway(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "Payment provider error", detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
