package com.aura.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aura.auth.jwt")
public record JwtProperties(

        @NotBlank(message = "JWT secret key must be provided")
        String secret,

        @NotNull
        @DefaultValue("86400000")
        long expirationMs
) {
}