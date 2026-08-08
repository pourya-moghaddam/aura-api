package com.aura.media.upload.dto;

import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deliberately omits the bucket and object key. A client's handle on a file is its id; where the
 * bytes physically live is this service's business and changes during promotion anyway.
 */
public record MediaFileResponse(
    UUID id,
    String filename,
    String contentType,
    Long sizeBytes,
    MediaStatus status,
    String failureReason,
    OffsetDateTime createdAt,
    OffsetDateTime readyAt
) {

    public static MediaFileResponse from(MediaFile file) {
        return new MediaFileResponse(
            file.getId(),
            file.getOriginalFilename(),
            // The detected type once known, falling back to the declared one while still pending.
            // Never report the declared type for a validated file - that would be reporting the
            // client's claim as though we had confirmed it.
            file.getDetectedContentType() != null
                ? file.getDetectedContentType()
                : file.getDeclaredContentType(),
            file.getSizeBytes(),
            file.getStatus(),
            file.getFailureReason(),
            file.getCreatedAt(),
            file.getReadyAt()
        );
    }
}
