package com.aura.common.web.correlation;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The MDC cleanup in the {@code finally} block is the part that matters. Servlet threads are
 * pooled, so a leaked entry does not fail anything — it silently stamps the *next* request handled
 * by that thread with the previous request's ID, which corrupts exactly the traces you reach for
 * when something has gone wrong.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("an inbound correlation ID is honoured, so a trace spans services")
    void inboundIdIsReused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationId.HEADER, "upstream-id");

        String[] seen = new String[1];
        FilterChain chain = mock(FilterChain.class);
        doAnswer(i -> seen[0] = MDC.get(CorrelationId.MDC_KEY)).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(seen[0]).isEqualTo("upstream-id");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("upstream-id");
    }

    @Test
    @DisplayName("a request with no ID gets one generated and echoed back")
    void idGeneratedWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[1];
        FilterChain chain = mock(FilterChain.class);
        doAnswer(i -> seen[0] = MDC.get(CorrelationId.MDC_KEY)).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(seen[0]).isNotBlank();
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(seen[0]);
    }

    @Test
    @DisplayName("a blank inbound header is treated as absent, not propagated as an empty trace")
    void blankHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationId.HEADER, "   ");

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    @DisplayName("the MDC is cleared after a successful request")
    void mdcClearedAfterSuccess() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the chain throws")
    void mdcClearedAfterFailure() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        doThrow(new IllegalStateException("boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() ->
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
            .isInstanceOf(IllegalStateException.class);

        // The failing request is the one most likely to be traced, and the one whose leaked ID
        // would mislabel whatever the pooled thread picks up next.
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("current() outside a request returns a usable ID rather than null")
    void currentOutsideARequest() {
        assertThat(CorrelationId.current()).isNotBlank();
    }

    @Test
    @DisplayName("generated IDs are unique")
    void generatedIdsAreUnique() {
        assertThat(CorrelationId.generate()).isNotEqualTo(CorrelationId.generate());
    }
}
