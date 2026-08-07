package com.aura.common.web;

import com.aura.common.web.correlation.CorrelationIdFilter;
import com.aura.common.web.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared web concerns into any servlet service that puts common-web on its classpath.
 *
 * <p>Auto-configuration rather than component scanning, because services scan their own package
 * ({@code com.aura.auth}, {@code com.aura.catalog}) and would otherwise each have to remember to
 * add {@code com.aura.common.web} — a step that gets forgotten exactly once, on the service where
 * it matters.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuraWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
