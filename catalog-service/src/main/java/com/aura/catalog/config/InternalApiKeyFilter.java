package com.aura.catalog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /api/internal/**}, which order-service calls to hold and settle stock.
 *
 * <p>These endpoints cannot use the normal JWT path. Guest checkout is a requirement, so the
 * shopper may have no token at all, and even when they do, "may this person buy" is a different
 * question from "may this service move stock". A shared key between the two services answers the
 * question actually being asked.
 *
 * <p>It is the second line, not the first: the gateway routes only {@code /api/auth},
 * {@code /api/catalog}, {@code /api/control/catalog} and {@code /api/media}, so nothing from
 * outside reaches these paths at all. This is what stands between them and anything that lands on
 * the internal network.
 */
@Slf4j
@RequiredArgsConstructor
public class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Api-Key";
    private static final String PROTECTED_PREFIX = "/api/internal/";

    private final InternalApiProperties properties;

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!request.getRequestURI().startsWith(PROTECTED_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!matches(request.getHeader(HEADER))) {
            log.warn("Rejected an internal API call to {} with a missing or wrong key",
                request.getRequestURI());
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison. A byte-by-byte {@code equals} leaks the key one character at a
     * time to anyone able to measure the difference, which for something on the internal network
     * is a realistic position to be in.
     */
    private boolean matches(String presented) {
        if (presented == null || !properties.isConfigured()) {
            return false;
        }
        return MessageDigest.isEqual(
            presented.getBytes(StandardCharsets.UTF_8),
            properties.apiKey().getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
            {"type":"https://aura.local/problems/unauthenticated","title":"Unauthorized",\
            "status":401,"detail":"This endpoint requires a valid internal API key.",\
            "errorCode":"unauthenticated"}""");
    }
}
