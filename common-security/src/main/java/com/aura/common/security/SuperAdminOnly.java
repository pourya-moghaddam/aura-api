package com.aura.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a control-audience token held by a {@code SUPER_ADMIN}.
 *
 * <p>Not {@link ControlPanelOnly} plus a separate {@code @PreAuthorize("hasRole(...)")} on the same
 * element — Spring Security allows exactly one {@code @PreAuthorize} (directly present or, as here,
 * meta-present) per method or class, and {@code ControlPanelOnly} already carries one. Stacking a
 * second throws {@code AnnotationConfigurationException} at request time, not at compile time, so
 * the first sign of the mistake is every request to that endpoint failing with a 500. This
 * annotation folds both checks into the single expression Spring requires.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@PreAuthorize("hasAuthority('AUD_control') and hasRole('SUPER_ADMIN')")
public @interface SuperAdminOnly {
}
