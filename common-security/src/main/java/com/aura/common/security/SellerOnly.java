package com.aura.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Control-panel endpoints a seller may reach, and which admins may reach too.
 *
 * <p>Admins are included because they need to be able to fix a seller's listing — the services
 * behind these endpoints scope by ownership, and treat an admin as able to act on any product
 * rather than only their own.
 *
 * <p>Composed rather than applied alongside a separate {@code @PreAuthorize}. Spring rejects two
 * sources of the same annotation on one method with an {@code AnnotationConfigurationException}
 * thrown at request time, not at startup — so the endpoint 500s on every call while the build stays
 * green. This has already caught the project out once; see {@link SuperAdminOnly}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@PreAuthorize("hasAuthority('AUD_control') and ("
    + "hasRole('SELLER') or hasRole('ADMIN') or hasRole('SUPER_ADMIN'))")
public @interface SellerOnly {
}
