package com.aura.media.upload;

import com.aura.media.download.DownloadService;
import com.aura.media.download.PublicMediaService;
import com.aura.media.upload.dto.MediaFileResponse;
import com.aura.media.upload.dto.UploadTicketRequest;
import com.aura.media.upload.dto.UploadTicketResponse;
import com.aura.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The media API.
 *
 * <p>Three steps, deliberately separate: ask for a ticket, upload to storage directly, tell us you
 * are done. The middle step does not involve this service at all — which is rule 1, and also why
 * finalize exists rather than the upload response simply meaning "uploaded".
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final UploadService uploadService;
    private final MediaQueryService mediaQueryService;
    private final DownloadService downloadService;
    private final PublicMediaService publicMediaService;

    /** Step 1: a presigned URL plus the id to refer to this file by from now on. */
    @PostMapping("/uploads")
    public ResponseEntity<UploadTicketResponse> requestUploadTicket(
        @Valid @RequestBody UploadTicketRequest request
    ) {
        return ResponseEntity.ok(uploadService.issueTicket(CurrentUser.requiredId(), request));
    }

    /**
     * Step 3: the client reports the upload finished.
     *
     * <p>202, not 200 — validation and scanning have been queued, not completed. Rule 6: this
     * returns immediately and the client polls status rather than blocking on a virus scan.
     */
    @PostMapping("/uploads/{mediaId}/finalize")
    public ResponseEntity<MediaFileResponse> finalizeUpload(@PathVariable UUID mediaId) {
        return ResponseEntity.accepted()
            .body(mediaQueryService.markUploaded(CurrentUser.requiredId(), mediaId));
    }

    /** Poll target for the client waiting to find out whether its file passed. */
    @GetMapping("/{mediaId}")
    public ResponseEntity<MediaFileResponse> get(@PathVariable UUID mediaId) {
        return ResponseEntity.ok(mediaQueryService.get(CurrentUser.requiredId(), mediaId));
    }

    @GetMapping
    public ResponseEntity<List<MediaFileResponse>> list() {
        return ResponseEntity.ok(mediaQueryService.listFor(CurrentUser.requiredId()));
    }

    /** A fresh signed URL each time; they are short-lived by design and not meant to be cached. */
    @GetMapping("/{mediaId}/download-url")
    public ResponseEntity<DownloadService.DownloadUrlResponse> downloadUrl(@PathVariable UUID mediaId) {
        return ResponseEntity.ok(downloadService.issueDownloadUrl(CurrentUser.requiredId(), mediaId));
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(@PathVariable UUID mediaId) {
        mediaQueryService.delete(CurrentUser.requiredId(), mediaId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Publishes a file so it can be shown to anonymous visitors.
     *
     * <p>Called by catalog-service when media is attached to a product, forwarding the seller's own
     * token — so the owner check below is what stops a seller publishing someone else's upload.
     */
    @PostMapping("/{mediaId}/publish")
    public ResponseEntity<MediaFileResponse> publish(@PathVariable UUID mediaId) {
        return ResponseEntity.ok(mediaQueryService.publish(CurrentUser.requiredId(), mediaId));
    }

    @PostMapping("/{mediaId}/unpublish")
    public ResponseEntity<MediaFileResponse> unpublish(@PathVariable UUID mediaId) {
        return ResponseEntity.ok(mediaQueryService.unpublish(CurrentUser.requiredId(), mediaId));
    }

    /**
     * The storefront's image URL. The only anonymous endpoint in this service.
     *
     * <p>Serves the bytes rather than redirecting to storage — see {@link PublicMediaService} for
     * why a redirect does not survive containerisation. Only {@code PUBLIC} + {@code READY} files
     * resolve here; everything else is a flat 404.
     *
     * <p>Cached as immutable for a year. That is not optimism: an object is promoted into the
     * serving bucket once and never rewritten, and editing a product's photo produces a new media
     * id rather than new bytes under the old one. So the URL genuinely does identify one fixed
     * sequence of bytes forever, which is exactly the condition {@code immutable} asks for, and it
     * is what keeps image traffic off this JVM in practice.
     */
    @GetMapping("/{mediaId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID mediaId) {
        PublicMediaService.PublicMedia media = publicMediaService.open(mediaId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .contentType(MediaType.parseMediaType(media.contentType()))
            // Inline, not attachment: these are page images, not downloads. Paired with the
            // nosniff header the service already sets, so a browser renders it as the type we
            // detected or not at all.
            .header("Content-Disposition", "inline");

        if (media.sizeBytes() != null) {
            response.contentLength(media.sizeBytes());
        }

        // The digest when we have one, the media id otherwise.
        //
        // Falling back matters: checksum_sha256 is not populated on every upload path, so keying
        // the ETag solely on it produced responses with no validator at all - a conditional
        // request then re-downloads the whole image. The id is still a strong validator here,
        // because the bytes behind a given id never change: an edit produces a new id rather than
        // rewriting the object.
        response.eTag("\"" + (media.checksumSha256() != null ? media.checksumSha256() : mediaId) + "\"");

        return response.body(media.body());
    }
}
