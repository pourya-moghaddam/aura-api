package com.aura.media.upload;

import com.aura.media.download.DownloadService;
import com.aura.media.upload.dto.MediaFileResponse;
import com.aura.media.upload.dto.UploadTicketRequest;
import com.aura.media.upload.dto.UploadTicketResponse;
import com.aura.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
