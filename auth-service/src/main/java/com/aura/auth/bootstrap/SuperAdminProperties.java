package com.aura.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the default super-admin, created once on first startup if no user holds
 * {@code SUPER_ADMIN} yet.
 *
 * <p>No defaults for either field. A hardcoded fallback phone or password is exactly the kind of
 * thing that ends up unchanged in a real deployment — the requirement is that these come from the
 * environment, with nothing to fall back to.
 *
 * @param phone    must normalize as a valid Iranian mobile number, the same as any other user's -
 *                 it shares the same {@code phone} column and login paths, so a syntactically
 *                 different placeholder would be unfindable by the normal password-login flow
 * @param password plaintext, read once at startup and immediately hashed; never logged
 */
@ConfigurationProperties("aura.auth.super-admin")
public record SuperAdminProperties(String phone, String password) {

    private static final int MIN_PASSWORD_LENGTH = 12;

    public boolean isConfigured() {
        return phone != null && !phone.isBlank() && password != null && !password.isBlank();
    }

    /**
     * Deliberately stricter than the 8-character minimum on {@code SetPasswordRequest}: this
     * account has unlimited privilege from the moment it exists, so it does not get the same
     * floor as a regular user's self-chosen password.
     */
    public boolean isPasswordStrongEnough() {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH;
    }
}
