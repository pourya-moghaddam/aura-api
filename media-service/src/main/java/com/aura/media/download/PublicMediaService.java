package com.aura.media.download;

import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.file.MediaVisibility;
import com.aura.media.storage.ObjectStorage;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Serves files that have been explicitly published, to callers who are not signed in.
 *
 * <p><strong>Why the bytes stream through this service instead of a redirect to storage.</strong>
 * The obvious implementation is a 302 to a presigned URL, and it is wrong here. Presigned URLs are
 * signed against {@code publicEndpoint} — the address a <em>browser</em> uses, which in Compose is
 * {@code http://localhost:9000}. The storefront's image optimiser fetches images server-side, from
 * inside its own container, where {@code localhost} is the storefront itself. The redirect would
 * resolve to nothing. Streaming keeps every request on the one origin the storefront already talks
 * to, which is the same invariant that makes the session cookies work.
 *
 * <p>The cost is that image bytes pass through this JVM. That is acceptable because the responses
 * are immutable and cached hard (see the controller): a promoted object never changes, so a client
 * or CDN that has fetched one never needs to ask again. If it ever stops being acceptable, the
 * answer is a CDN in front of this endpoint, not a redirect — the URL stays stable either way.
 */
@Service
@RequiredArgsConstructor
public class PublicMediaService {

    private final MediaFileRepository mediaFileRepository;
    private final ObjectStorage objectStorage;

    /**
     * @throws ResourceNotFoundException when the file does not exist, is not published, or has not
     *         passed scanning. All three are one 404 on purpose: distinguishing them tells an
     *         anonymous caller which ids exist and which are merely private, which is exactly the
     *         probing the UUID primary key was chosen to prevent.
     */
    @Transactional(readOnly = true)
    public PublicMedia open(UUID mediaId) {
        MediaFile file = mediaFileRepository
            .findByIdAndVisibilityAndStatus(mediaId, MediaVisibility.PUBLIC, MediaStatus.READY)
            .orElseThrow(() -> ResourceNotFoundException.of("Media", mediaId));

        // The stream is handed to the servlet container to copy and close. It is not wrapped in a
        // try-with-resources here: closing it before the response is written would truncate it.
        InputStreamResource body =
            new InputStreamResource(objectStorage.openStream(file.getBucket(), file.getObjectKey()));

        return new PublicMedia(
            body,
            // The detected type, never the declared one. Serving a caller's own unverified claim
            // back to other browsers as a Content-Type is how an "image" gets sniffed as HTML.
            file.getDetectedContentType(),
            file.getSizeBytes(),
            // Doubles as a cache validator, so a conditional request can be answered 304 without
            // reading a byte from storage.
            file.getChecksumSha256());
    }

    public record PublicMedia(
        InputStreamResource body,
        String contentType,
        Long sizeBytes,
        String checksumSha256
    ) {
    }
}
