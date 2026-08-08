package com.aura.media.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the binding of {@link UploadPolicyProperties}, not its logic.
 *
 * <p>Worth its own test because the failure it catches is silent. Spring's relaxed binding treats
 * {@code /} as structure, so an unquoted {@code video/mp4:} map key never binds — the map comes out
 * empty, every content type falls back to the default ceiling, and uploads carry on working at
 * entirely the wrong limit with nothing in the logs. The real application.yml was written that way
 * and the mistake only surfaced when a 40MB video was rejected against a 50MB cap.
 */
class UploadPolicyPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(UploadPolicyProperties.class)
    static class TestConfig {
    }

    @Test
    void bracketQuotedMapKeysWithSlashesBind() {
        runner.withPropertyValues(
                "aura.media.upload.max-size-bytes-by-type.[image/png]=2097152",
                "aura.media.upload.max-size-bytes-by-type.[video/mp4]=52428800")
            .run(context -> {
                UploadPolicyProperties policy = context.getBean(UploadPolicyProperties.class);

                assertThat(policy.maxSizeBytesByType())
                    .as("map keys containing '/' must survive relaxed binding")
                    .containsEntry("image/png", 2097152L)
                    .containsEntry("video/mp4", 52428800L);

                assertThat(policy.maxSizeFor("video/mp4")).isEqualTo(52428800L);
            });
    }

    /** An unmapped type gets the default, which is the intended fallback rather than a bug. */
    @Test
    void anUnmappedTypeFallsBackToTheDefault() {
        runner.withPropertyValues(
                "aura.media.upload.default-max-size-bytes=2097152",
                "aura.media.upload.max-size-bytes-by-type.[image/png]=5242880")
            .run(context -> {
                UploadPolicyProperties policy = context.getBean(UploadPolicyProperties.class);
                assertThat(policy.maxSizeFor("image/webp")).isEqualTo(2097152L);
                assertThat(policy.maxSizeFor("image/png")).isEqualTo(5242880L);
            });
    }

    @Test
    void allowlistIsEnforcedCaseInsensitively() {
        runner.withPropertyValues(
                "aura.media.upload.allowed-content-types[0]=image/png",
                "aura.media.upload.allowed-content-types[1]=video/mp4")
            .run(context -> {
                UploadPolicyProperties policy = context.getBean(UploadPolicyProperties.class);
                assertThat(policy.isAllowed("image/png")).isTrue();
                assertThat(policy.isAllowed("IMAGE/PNG")).isTrue();
                assertThat(policy.isAllowed("application/x-msdownload")).isFalse();
                assertThat(policy.isAllowed(null)).isFalse();
            });
    }
}
