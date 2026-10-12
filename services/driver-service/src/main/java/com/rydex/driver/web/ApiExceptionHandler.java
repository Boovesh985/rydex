package com.rydex.driver.web;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders every API error as RFC 7807 Problem Details. Validation errors are handled by the superclass. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setTitle(ex.title());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return response.body(pd);
    }

    /** Fallback for constraint violations raised outside MVC's own parameter validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad request");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return conflict("Concurrent modification", "The resource was modified concurrently; reload and retry");
    }

    /** Unique indexes (e.g. vehicle number) are the source of truth for duplicates. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        return conflict("Conflict", "The request conflicts with existing data");
    }

    private static ResponseEntity<ProblemDetail> conflict(String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        pd.setTitle(title);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }
}
