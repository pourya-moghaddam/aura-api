package com.aura.common.web.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a correlation ID in the SLF4J MDC for the life of the request, so every log line from every
 * service involved in one user action can be joined together afterwards.
 *
 * <p>Runs at highest precedence: a request that fails in a later filter (auth, rate limiting) is
 * exactly the one you most want to be able to trace.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationId.generate();
        }

        org.slf4j.MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled; a stale MDC entry would silently mislabel the next
            // request handled by this thread.
            org.slf4j.MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
