package com.aura.media.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param contentType what the client says it is about to upload. Checked against the allowlist and
 *                    bound into the presigned URL's signature — and then distrusted entirely at
 *                    finalize, which looks at the actual bytes.
 * @param sizeBytes   declared size, checked against the per-type ceiling before a URL is issued.
 *                    Also unverified: it is a hint that lets an oversized upload be rejected before
 *                    it consumes bandwidth, not a guarantee. The real size is read back from
 *                    storage at finalize.
 * @param filename    for display only. Never used to build an object key.
 */
public record UploadTicketRequest(
    @NotBlank(message = "Content type is required")
    String contentType,

    @Positive(message = "Size must be positive")
    long sizeBytes,

    @Size(max = 255)
    String filename
) {
}
