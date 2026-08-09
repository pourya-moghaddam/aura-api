package com.aura.media.scan;

import java.io.InputStream;

/**
 * The virus scan step of rule 4.
 *
 * <p>An interface rather than a direct ClamAV call because <em>whether</em> a gate exists between
 * upload and access is architecture, while <em>which</em> scanner sits in it is policy. Keeping
 * them separate means the quarantine-scan-promote state machine is testable, and buildable, without
 * a 450MB virus database being present.
 */
public interface VirusScanner {

    /**
     * @param content the full object stream. Unlike content-type detection, a scanner genuinely
     *                needs every byte — a signature can sit anywhere in the file.
     */
    ScanResult scan(InputStream content);

    /**
     * @param infected  whether the file should be rejected
     * @param signature what matched, for the audit trail. Null when clean.
     */
    record ScanResult(boolean infected, String signature) {

        public static ScanResult clean() {
            return new ScanResult(false, null);
        }

        public static ScanResult infected(String signature) {
            return new ScanResult(true, signature);
        }
    }
}
