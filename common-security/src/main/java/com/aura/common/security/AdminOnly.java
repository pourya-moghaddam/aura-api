package com.aura.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a control-panel token belonging to an admin.
 *
 * <p>Guards what an admin defines and a seller merely picks from: the shared catalogue vocabulary
 * — categories, colours, sizes, fields, banners — and the delivery methods checkout prices against.
 * {@code SUPER_ADMIN} is included because it is strictly more privileged, not because the two roles
 * are interchangeable.
 *
 * <p>Named for the role rather than a domain. It was {@code CatalogAdminOnly} while catalog was
 * the only thing it guarded, which read as though the rule were catalog-specific; it never was, and
 * the name was about to cause a second identical annotation to be written for order-service.
 *
 * <p>One combined expression rather than {@link ControlPanelOnly} plus a separate
 * {@code @PreAuthorize} on the same element. Spring Security permits exactly one
 * {@code @PreAuthorize} per method or class, directly present or meta-present, and
 * {@code ControlPanelOnly} already carries one; stacking a second throws
 * {@code AnnotationConfigurationException} at request time rather than compile time, so the first
 * sign is every call to the endpoint returning 500.
 *
 * <p>Both halves are load-bearing. A control-audience token only proves the holder cleared
 * <em>some</em> control-role check at login — a seller has one too.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@PreAuthorize("hasAuthority('AUD_control') and (hasRole('ADMIN') or hasRole('SUPER_ADMIN'))")
public @interface AdminOnly {
}
