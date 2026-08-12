package com.aura.common.web.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status each exception carries is what the client actually receives, and it is set once here
 * rather than at every throw site. A wrong value is invisible in the service that throws it and
 * shows up as the wrong HTTP code in every caller.
 */
class ApplicationExceptionTest {

    @Test
    @DisplayName("a missing resource is a 404 naming what was not found")
    void resourceNotFound() {
        ResourceNotFoundException ex = ResourceNotFoundException.of("Category", 42L);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("resource-not-found");
        assertThat(ex.getMessage()).contains("Category").contains("42");
    }

    @Test
    @DisplayName("a missing resource identified by a string reads the same way")
    void resourceNotFoundBySlug() {
        assertThat(ResourceNotFoundException.of("Category", "shoes").getMessage()).contains("shoes");
    }

    @Test
    @DisplayName("a conflict is a 409 and keeps its machine-readable code")
    void conflict() {
        ConflictException ex = new ConflictException("color-name-taken", "A colour named 'Red' exists.");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("color-name-taken");
        assertThat(ex.getMessage()).contains("Red");
    }

    @Test
    @DisplayName("a broken business rule is a 422, not a 400")
    void businessRule() {
        // 400 means "I could not parse this"; 422 means "I understood it and it is not allowed".
        // Clients distinguish the two - one is a bug in the request, the other is a rule.
        BusinessRuleException ex = new BusinessRuleException("category-cycle", "Cannot nest under itself.");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo("category-cycle");
    }

    @Test
    @DisplayName("a rate limit is a 429 so clients know to back off rather than retry immediately")
    void rateLimit() {
        RateLimitExceededException ex =
            new RateLimitExceededException("otp-throttled", "Wait before requesting another code.");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getErrorCode()).isEqualTo("otp-throttled");
    }

    @Test
    @DisplayName("every one of them is an ApplicationException, so one handler covers them all")
    void allShareTheBaseType() {
        assertThat(ResourceNotFoundException.of("X", 1L)).isInstanceOf(ApplicationException.class);
        assertThat(new ConflictException("a", "b")).isInstanceOf(ApplicationException.class);
        assertThat(new BusinessRuleException("a", "b")).isInstanceOf(ApplicationException.class);
        assertThat(new RateLimitExceededException("a", "b")).isInstanceOf(ApplicationException.class);
    }
}
