package com.aura.media.scan;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * Passes everything. The development default.
 *
 * <p>Logs a warning on every call rather than staying quiet, so that a production deployment that
 * forgot to set {@code aura.media.scan.provider} is loudly obvious in the logs instead of silently
 * accepting malware for months.
 */
@Slf4j
public class NoOpVirusScanner implements VirusScanner {

    @Override
    public ScanResult scan(InputStream content) {
        log.warn("Virus scanning is DISABLED - every uploaded file is being accepted unscanned. "
            + "Set aura.media.scan.provider=clamav before serving real traffic.");
        return ScanResult.clean();
    }
}
