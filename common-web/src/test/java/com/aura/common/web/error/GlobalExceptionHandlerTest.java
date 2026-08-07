package com.aura.common.web.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for a bug found by exercising the real endpoints: {@code @PreAuthorize}
 * failures throw {@link AccessDeniedException} from inside the controller-method AOP proxy, which
 * routes through the normal {@code @ControllerAdvice} chain — the same path as any other exception.
 * Without an explicit handler here, it fell through to the {@code Exception.class} catch-all and
 * came back as a 500 "unexpected error" instead of a 403, on every method-security check in every
 * service using this class.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("GET");
        return request;
    }

    @Test
    void accessDeniedBecomesA403NotA500() {
        ProblemDetail body = handler.handleAccessDenied(
            new AccessDeniedException("denied"), request("/api/auth/control/users"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(body.getProperties()).containsEntry("errorCode", "access-denied");
    }

    @Test
    void authenticationFailureBecomesA401() {
        ProblemDetail body = handler.handleAuthenticationException(
            new BadCredentialsException("bad creds"), request("/api/auth/control/users"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(body.getProperties()).containsEntry("errorCode", "unauthenticated");
    }

    @Test
    void anApplicationExceptionKeepsItsOwnStatusAndCode() {
        ApplicationException custom = new ApplicationException(HttpStatus.CONFLICT, "already-exists", "nope") { };

        ProblemDetail body = handler.handleApplicationException(custom, request("/api/auth/control/users"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.getProperties()).containsEntry("errorCode", "already-exists");
    }

    /** Confirms the catch-all still only fires for exceptions that are genuinely unmapped. */
    @Test
    void aTrulyUnexpectedExceptionStillBecomesA500() {
        ProblemDetail body = handler.handleUnexpected(
            new RuntimeException("something broke"), request("/api/auth/control/users"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(body.getProperties()).containsEntry("errorCode", "internal-error");
        // The client gets nothing more specific than that - the raw message must never leak.
        assertThat(body.getDetail()).doesNotContain("something broke");
    }
}
