package com.aura.media.config;

import com.aura.media.scan.ClamAvVirusScanner;
import com.aura.media.scan.NoOpVirusScanner;
import com.aura.media.scan.VirusScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ScanConfig {

    @ConfigurationProperties(prefix = "aura.media.scan")
    public record ScanProperties(
        @DefaultValue("none") String provider,
        @DefaultValue("clamav") String clamavHost,
        @DefaultValue("3310") int clamavPort
    ) {
    }

    @Bean
    public VirusScanner virusScanner(ScanProperties properties) {
        if ("clamav".equalsIgnoreCase(properties.provider())) {
            log.info("Virus scanning via clamd at {}:{}", properties.clamavHost(), properties.clamavPort());
            return new ClamAvVirusScanner(properties.clamavHost(), properties.clamavPort());
        }

        log.warn("Virus scanning is disabled (aura.media.scan.provider={})", properties.provider());
        return new NoOpVirusScanner();
    }
}
