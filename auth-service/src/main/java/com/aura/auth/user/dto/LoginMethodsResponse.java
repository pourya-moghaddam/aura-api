package com.aura.auth.user.dto;

/**
 * What the login screen should offer for a given phone.
 *
 * @param otp      always true — OTP is the universal fallback
 * @param password true when the account has a password set, which is what makes the UI default to
 *                 the password form instead of the OTP one
 */
public record LoginMethodsResponse(boolean otp, boolean password) {
}
