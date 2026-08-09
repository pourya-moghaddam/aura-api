package com.aura.media.storage;

/**
 * Object storage misbehaved. Distinct from a verdict about a file: this means our infrastructure
 * failed, so the affected upload is marked FAILED (retryable) rather than QUARANTINED.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
