package com.aura.common.web.error;

import com.aura.common.web.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders every error as RFC 9457 {@code application/problem+json}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's own exceptions (unreadable
 * body, missing parameter, method not allowed) also come out as problem+json rather than in
 * Boot's default error shape. Without that, clients have to parse two different error formats.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI TYPE_BASE = URI.create("https://aura.local/problems/");

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(ApplicationException ex, HttpServletRequest request) {
        // Expected outcomes: log at debug. These are not defects and should not page anyone.
        log.debug("Handled application exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setType(TYPE_BASE.resolve("validation-failed"));
        body.setTitle("Validation failed");
        body.setDetail("One or more fields are invalid.");
        body.setProperty("errorCode", "validation-failed");
        body.setProperty("fieldErrors", fieldErrors);
        body.setProperty("correlationId", CorrelationId.current());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Anything not deliberately mapped is a defect. The client gets a correlation ID and nothing
     * else — exception messages routinely contain table names, SQL fragments, and internal
     * hostnames, none of which should cross the network boundary.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "An unexpected error occurred. Quote the correlation ID when reporting this.",
            request
        );
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(TYPE_BASE.resolve(errorCode));
        body.setTitle(status.getReasonPhrase());
        body.setDetail(detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("errorCode", errorCode);
        body.setProperty("correlationId", CorrelationId.current());
        return body;
    }
}
