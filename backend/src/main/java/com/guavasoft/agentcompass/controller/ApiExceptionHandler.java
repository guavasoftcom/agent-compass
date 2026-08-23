package com.guavasoft.agentcompass.controller;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps request-param constraint violations (e.g. {@code @Min}/{@code @Max} on a bare
 * {@code @RequestParam}) to a 400 response.
 *
 * <p>{@code @Valid @ModelAttribute} failures (e.g. {@link com.guavasoft.agentcompass.validation.ValidDateRange})
 * already resolve to 400 automatically via Spring MVC's {@code MethodArgumentNotValidException}
 * handling and need no help here. But a bare {@code @RequestParam} constraint — combined with
 * the class-level {@code @Validated} that dashboard controllers use for method-parameter
 * validation — is checked by the older Bean Validation AOP proxy, which throws a raw
 * {@link ConstraintViolationException} that Spring's default exception resolvers don't map to
 * a status code, surfacing as an unhandled 500 without this handler.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<String> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }

    /**
     * Rejected arguments a service checked for itself, rather than a Bean Validation annotation —
     * currently the purge endpoint's confirmation phrase, which cannot be expressed as a constraint
     * because the required value lives on the service that owns the operation. Without this the
     * refusal would surface as a 500, reading like the purge failed rather than like it was
     * declined.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
}
