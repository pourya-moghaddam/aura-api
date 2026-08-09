package com.aura.common.web.error;

import com.aura.common.web.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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
     * {@code @PreAuthorize} failures throw this from inside the AOP proxy around the controller
     * method, which puts it on the normal {@code @ControllerAdvice} resolution path — the same one
     * every other exception here goes through. Without an explicit handler, it fell through to
     * {@link #handleUnexpected} and came back as a 500 instead of a 403: confirmed live, a
     * storefront-audience token hitting a {@code @PreAuthorize}-protected control endpoint got
     * "unexpected error" instead of "forbidden". This affects every method-security check in every
     * service that uses this handler, not just the one that surfaced it.
     *
     * <p>{@code AuthenticationException} is handled here too for the same structural reason, even
     * though the resource-server filter chain currently intercepts unauthenticated requests before
     * they reach a controller at all (confirmed: a request with no token already returns 401
     * correctly). That filter-level handling is what happens to catch today's cases, not a
     * guarantee for every future one — a controller-level auth check taken later would hit this
     * same gap the access-denied case did.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.debug("Access denied on {} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.FORBIDDEN, "access-denied",
            "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.debug("Authentication failed on {} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication is required.", request);
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
