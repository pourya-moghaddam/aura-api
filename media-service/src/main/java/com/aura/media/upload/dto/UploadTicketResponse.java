package com.aura.media.upload.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * @param mediaId    the handle the client uses from here on: to finalize, to poll status, and to
 *                   reference the file from a product. The object key is never exposed.
 * @param uploadUrl  presigned PUT target. Expires; see {@code expiresAt}.
 * @param headers    headers the client must send with the PUT. Content-Type is signed into the URL,
 *                   so omitting or changing it makes the signature invalid — returned explicitly
 *                   because that failure is otherwise a baffling 403 from storage.
 */
public record UploadTicketResponse(
    UUID mediaId,
    String uploadUrl,
    Instant expiresAt,
    java.util.Map<String, String> headers
) {
}
