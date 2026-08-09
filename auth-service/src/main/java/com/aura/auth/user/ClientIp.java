package com.aura.auth.user;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetSocketAddress;

/**
 * Best-effort source address of a request, for per-IP rate limiting.
 *
 * <p>Reads the first hop of {@code X-Forwarded-For}. That is only trustworthy because Nginx sits at
 * the edge and <em>overwrites</em> rather than appends to the header, and because this service is
 * not reachable directly. If either of those stops being true, a caller can set the header itself
 * and get a fresh rate-limit bucket per request — so the deployment assumption is load-bearing.
 */
final class ClientIp {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientIp() {
    }

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
