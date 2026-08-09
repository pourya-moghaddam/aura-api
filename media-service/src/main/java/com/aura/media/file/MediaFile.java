package com.aura.media.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "media_files")
@Getter
@Setter
@NoArgsConstructor
public class MediaFile {

    /**
     * Assigned by the application, not the database, because the id is needed to build the object
     * key before the row is written — the presigned URL has to name a key, and that happens in the
     * same call that creates this record.
     */
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "declared_content_type", nullable = false, length = 100)
    private String declaredContentType;

    /** What the leading bytes say it really is. Null until validation has run. */
    @Column(name = "detected_content_type", length = 100)
    private String detectedContentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(nullable = false, length = 63)
    private String bucket;

    @Column(name = "object_key", nullable = false, columnDefinition = "TEXT")
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    public static MediaFile pending(UUID id, Long ownerId, String bucket, String objectKey,
                                    String declaredContentType, String originalFilename) {
        MediaFile file = new MediaFile();
        file.id = id;
        file.ownerId = ownerId;
        file.bucket = bucket;
        file.objectKey = objectKey;
        file.declaredContentType = declaredContentType;
        file.originalFilename = originalFilename;
        file.status = MediaStatus.PENDING;
        file.createdAt = OffsetDateTime.now();
        file.updatedAt = file.createdAt;
        return file;
    }

    /** Promotion to the serving bucket: the object moved, so both location fields change with it. */
    public void markReady(String servingBucket, String servingObjectKey) {
        this.bucket = servingBucket;
        this.objectKey = servingObjectKey;
        this.status = MediaStatus.READY;
        this.readyAt = OffsetDateTime.now();
        this.failureReason = null;
    }

    public void markQuarantined(String reason) {
        this.status = MediaStatus.QUARANTINED;
        this.failureReason = reason;
    }

    public void markFailed(String reason) {
        this.status = MediaStatus.FAILED;
        this.failureReason = reason;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
