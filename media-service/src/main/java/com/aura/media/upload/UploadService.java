package com.aura.media.upload;

import com.aura.media.config.StorageProperties;
import com.aura.media.config.UploadPolicyProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.upload.dto.UploadTicketRequest;
import com.aura.media.upload.dto.UploadTicketResponse;
import com.aura.common.web.error.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Issues upload tickets.
 *
 * <p>The bytes go straight from the client to storage; this service only decides whether they are
 * allowed to and writes down that it happened. That is rule 1, and it is also why every limit is
 * checked here: once the presigned URL leaves this method, there is no further opportunity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final MediaFileRepository mediaFileRepository;
    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;
    private final UploadPolicyProperties uploadPolicy;
    private final UploadRateLimiter uploadRateLimiter;

    @Transactional
    public UploadTicketResponse issueTicket(long ownerId, UploadTicketRequest request) {
        String contentType = normalize(request.contentType());

        // Allowlist, not blocklist: the set of types we serve is small and known, the set that
        // could hurt us is open-ended.
        if (!uploadPolicy.isAllowed(contentType)) {
            throw new BusinessRuleException("unsupported-content-type",
                "Files of type " + contentType + " cannot be uploaded.");
        }

        long maxSize = uploadPolicy.maxSizeFor(contentType);
        if (request.sizeBytes() > maxSize) {
            throw new BusinessRuleException("file-too-large",
                "Files of type " + contentType + " may be at most " + maxSize + " bytes.");
        }

        uploadRateLimiter.checkAndConsume(ownerId);

        UUID mediaId = UUID.randomUUID();
        String objectKey = ObjectKeys.forUpload(mediaId, LocalDate.now());
        String quarantine = storageProperties.quarantineBucket();

        // Quarantine, always. A presigned URL is a write credential, and the only bucket it may
        // ever point at is the one nothing is served from.
        MediaFile mediaFile = MediaFile.pending(
            mediaId, ownerId, quarantine, objectKey, contentType, request.filename());
        mediaFileRepository.save(mediaFile);

        String uploadUrl = objectStorage.presignUpload(quarantine, objectKey, contentType);

        log.debug("Issued upload ticket {} for owner {} ({})", mediaId, ownerId, contentType);

        return new UploadTicketResponse(
            mediaId,
            uploadUrl,
            Instant.now().plus(storageProperties.uploadUrlTtl()),
            // Content-Type is part of the signature. Sending a different one, or none, fails with
            // a 403 from storage that gives no hint why — so it is spelled out here.
            Map.of("Content-Type", contentType));
    }

    private String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }
}
