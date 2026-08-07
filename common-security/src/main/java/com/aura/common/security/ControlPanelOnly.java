package com.aura.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a control-audience token.
 *
 * <p>The gateway already blocks storefront tokens on {@code /api/control/**}, so this is defence in
 * depth — it keeps the rule true if a control endpoint is ever mounted on an unexpected path, or if
 * something reaches the service without passing through the gateway.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@PreAuthorize("hasAuthority('AUD_control')")
public @interface ControlPanelOnly {
}
