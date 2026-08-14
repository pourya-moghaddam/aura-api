package com.aura.common.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * The parts of the API description that are the same in every service.
 *
 * <p>Written once here rather than copied into five services: the bearer scheme in particular is
 * the difference between a documentation page you can read and one you can actually use, and five
 * copies means four of them eventually drift.
 *
 * <p>The title comes from {@code spring.application.name}, so a service that is added later is
 * described correctly without anyone remembering to write a description for it.
 */
public final class AuraOpenApiCustomiser {

    private static final String BEARER = "bearer-jwt";

    private AuraOpenApiCustomiser() {
    }

    public static OpenAPI describe(String applicationName) {
        return new OpenAPI()
            .info(new Info()
                .title(applicationName)
                .version("v1")
                .description("""
                    Storefront endpoints live under /api/{service}, control-panel endpoints under \
                    /api/control/{service} and need a token whose `aud` claim is `control`. \
                    Errors are RFC 9457 problem+json with a stable `type`."""))
            .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("""
                    Access token from /api/auth. Fifteen minutes, RS256, validated against the \
                    JWKS endpoint - so a token minted for the storefront will not open a control \
                    endpoint however it is pasted in here.""")));
    }
}
