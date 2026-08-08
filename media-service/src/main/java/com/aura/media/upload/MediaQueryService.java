package com.aura.media.upload;

import com.aura.media.config.StorageProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.upload.dto.MediaFileResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads and lifecycle transitions driven by the client, as opposed to the ones the worker performs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQueryService {

    private final MediaFileRepository mediaFileRepository;
    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;

    @Transactional(readOnly = true)
    public MediaFileResponse get(long ownerId, UUID mediaId) {
        return MediaFileResponse.from(requireOwned(ownerId, mediaId));
    }

    @Transactional(readOnly = true)
    public List<MediaFileResponse> listFor(long ownerId) {
        return mediaFileRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
            .map(MediaFileResponse::from)
            .toList();
    }

    /**
     * Client-driven transition from {@code PENDING} to {@code UPLOADED}, which is what makes the
     * file visible to the processing worker.
     *
     * <p>Idempotent by necessity: clients retry, and a double finalize must not reset a file that
     * has already been validated back into the queue. Only {@code PENDING} moves.
     */
    @Transactional
    public MediaFileResponse markUploaded(long ownerId, UUID mediaId) {
        MediaFile file = requireOwned(ownerId, mediaId);

        if (file.getStatus() == MediaStatus.PENDING) {
            file.setStatus(MediaStatus.UPLOADED);
            mediaFileRepository.save(file);
        } else {
            log.debug("Finalize called on {} already in state {}; ignoring",
                mediaId, file.getStatus());
        }

        return MediaFileResponse.from(file);
    }

    /**
     * Deletes the row and the object.
     *
     * <p>Only the row is guaranteed gone. If the object delete fails, the reaper picks up the
     * orphan later — the opposite order would leave a row pointing at bytes that no longer exist,
     * which reads as a broken file rather than an absent one.
     */
    @Transactional
    public void delete(long ownerId, UUID mediaId) {
        MediaFile file = requireOwned(ownerId, mediaId);

        if (file.getStatus() == MediaStatus.SCANNING) {
            // Deleting mid-scan would have the worker promoting an object whose row just vanished.
            throw new BusinessRuleException("media-busy",
                "This file is still being processed. Try again shortly.");
        }

        String bucket = file.getBucket();
        String objectKey = file.getObjectKey();
        mediaFileRepository.delete(file);

        try {
            objectStorage.delete(bucket, objectKey);
        } catch (RuntimeException e) {
            log.warn("Deleted media row {} but could not remove {}/{}; it will be reaped later",
                mediaId, bucket, objectKey, e);
        }
    }

    private MediaFile requireOwned(long ownerId, UUID mediaId) {
        return mediaFileRepository.findByIdAndOwnerId(mediaId, ownerId)
            .orElseThrow(() -> ResourceNotFoundException.of("Media", mediaId));
    }
}
