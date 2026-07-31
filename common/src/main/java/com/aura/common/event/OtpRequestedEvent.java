package com.aura.common.event;

public record OtpRequestedEvent(
    String phone,
    String otpCode,
    String message
) {
}