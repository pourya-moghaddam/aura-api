package com.aura.media.download;

import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.config.StorageProperties;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues short-lived download URLs — rule 5.
 *
 * <p>Two guarantees hold here. A raw bucket path never leaves this service, and nothing that has
 * not reached {@code READY} is downloadable at all — which is what makes the scan gate meaningful
 * rather than advisory. Quarantined content in particular must never be fetchable, or the whole
 * pipeline is theatre.
 */
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final MediaFileRepository mediaFileRepository;
    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;

    @Transactional(readOnly = true)
    public DownloadUrlResponse issueDownloadUrl(long requesterId, UUID mediaId) {
        // Scoped by owner. Product images will eventually need to be readable by anyone browsing
        // the storefront, but that is a different question from "may this user fetch this file",
        // and it will be answered by catalog-service asking on the shopper's behalf rather than by
        // loosening the check here.
        MediaFile file = mediaFileRepository.findByIdAndOwnerId(mediaId, requesterId)
            .orElseThrow(() -> ResourceNotFoundException.of("Media", mediaId));

        if (!file.getStatus().isServable()) {
            throw new BusinessRuleException("media-not-ready",
                "This file is not available for download.");
        }

        String url = objectStorage.presignDownload(file.getBucket(), file.getObjectKey());

        return new DownloadUrlResponse(
            mediaId, url, Instant.now().plus(storageProperties.downloadUrlTtl()));
    }

    public record DownloadUrlResponse(UUID mediaId, String url, Instant expiresAt) {
    }
}
