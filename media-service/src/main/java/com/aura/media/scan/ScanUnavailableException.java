package com.aura.media.scan;

/**
 * The scanner could not deliver a verdict.
 *
 * <p>Distinct from "the file is infected", and the distinction is the whole point: an unreachable
 * scanner must fail the file into a retryable state, never quietly let it through. Fail closed.
 */
public class ScanUnavailableException extends RuntimeException {

    public ScanUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
