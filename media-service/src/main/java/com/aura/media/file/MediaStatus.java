package com.aura.media.file;

/**
 * The gate between upload and access.
 *
 * <p>Only {@link #READY} is servable. Everything before it means the bytes exist somewhere but
 * have not been proven to be what they claim, and the two terminal failures are deliberately
 * distinct: {@link #QUARANTINED} is a verdict about the file, {@link #FAILED} is a verdict about
 * our own infrastructure. Retrying the second is correct; retrying the first is not.
 */
public enum MediaStatus {

    /** Presigned URL issued. The client may never actually upload, so this can be abandoned. */
    PENDING,

    /** Client confirmed the upload. Object is in quarantine, unvalidated. */
    UPLOADED,

    /** Content validation and virus scan in flight. */
    SCANNING,

    /** Passed everything and promoted to the serving bucket. The only downloadable state. */
    READY,

    /** Failed magic-byte validation or virus scan. Never servable, never retried. */
    QUARANTINED,

    /** Processing broke for a reason unrelated to the file itself. Safe to retry. */
    FAILED;

    public boolean isServable() {
        return this == READY;
    }

    public boolean isTerminal() {
        return this == READY || this == QUARANTINED;
    }
}
