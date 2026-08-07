package com.aura.common.web.correlation;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Access to the current request's correlation ID.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** The current correlation ID, or a generated one if called outside a request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? value : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
