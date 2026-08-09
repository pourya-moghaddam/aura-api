package com.aura.media.upload;

import com.aura.media.config.StorageProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.scan.ScanUnavailableException;
import com.aura.media.scan.VirusScanner;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.validation.ContentTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The gate between upload and access — rule 4.
 *
 * <p>Runs after the client has uploaded to quarantine and called finalize. Nothing here is on a
 * request thread: the client gets its response as soon as the upload is recorded, and this decides
 * separately whether the file ever becomes servable.
 *
 * <p>Order matters. Content type is checked before the virus scan because it is enormously cheaper
 * — a ranged read of sixteen bytes versus streaming the whole object — and most rejections are
 * mislabelled files rather than actual malware.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

    private final MediaFileRepository mediaFileRepository;
    private final ObjectStorage objectStorage;
    private final ContentTypeDetector contentTypeDetector;
    private final VirusScanner virusScanner;
    private final StorageProperties storageProperties;

    /**
     * Atomically takes ownership of a batch of finalized uploads.
     *
     * <p>Two things have to happen together here. {@code FOR UPDATE SKIP LOCKED} stops two
     * instances selecting the same rows, but those locks die at commit — so the same transaction
     * must also move the rows out of {@code UPLOADED}, or the next poll re-claims work already in
     * flight. Marking them {@code SCANNING} is what makes the claim stick.
     *
     * <p>Its own short transaction, separate from processing: holding row locks for however long it
     * takes to scan a batch of videos would block every other instance for the duration.
     */
    @Transactional
    public List<MediaFile> claimBatch(int batchSize) {
        List<MediaFile> claimed = mediaFileRepository.claimUploadedForProcessing(batchSize);
        claimed.forEach(file -> file.setStatus(MediaStatus.SCANNING));
        return mediaFileRepository.saveAll(claimed);
    }

    /**
     * Validates one file and, if it passes, promotes it to the serving bucket.
     *
     * <p>Each file is its own transaction. One poisoned upload must not roll back the verdicts on
     * everything else processed in the same batch.
     */
    @Transactional
    public void process(MediaFile file) {
        String quarantine = storageProperties.quarantineBucket();

        try {
            // Confirm the bytes are actually there. A client can call finalize without ever having
            // uploaded, and promoting a nonexistent object would produce a READY row pointing at
            // nothing - a file that looks fine until someone tries to fetch it.
            var head = objectStorage.head(quarantine, file.getObjectKey());
            if (head.isEmpty()) {
                reject(file, "No uploaded object found.");
                return;
            }

            file.setSizeBytes(head.get().contentLength());

            if (!validateContentType(file, quarantine)) {
                return;
            }

            if (!scanForViruses(file, quarantine)) {
                return;
            }

            promote(file, quarantine);

        } catch (ScanUnavailableException e) {
            // Infrastructure, not the file. FAILED is retryable; QUARANTINED is a verdict we have
            // not actually reached.
            log.error("Scanner unavailable while processing {}", file.getId(), e);
            file.markFailed("Scanner unavailable.");
            mediaFileRepository.save(file);
        } catch (RuntimeException e) {
            log.error("Processing failed for {}", file.getId(), e);
            file.markFailed("Processing error.");
            mediaFileRepository.save(file);
        }
    }

    /**
     * Rule 2. Reads only the leading bytes — enough to identify the format, without streaming a
     * 500MB video through this process.
     */
    private boolean validateContentType(MediaFile file, String quarantine) {
        byte[] prefix = objectStorage.readPrefix(
            quarantine, file.getObjectKey(), ContentTypeDetector.PREFIX_BYTES);

        Optional<String> detected = contentTypeDetector.detect(prefix);

        if (detected.isEmpty()) {
            reject(file, "File content does not match any supported format.");
            return false;
        }

        file.setDetectedContentType(detected.get());

        // The declared type is the client's claim; the detected type is what the bytes say. A
        // mismatch is the rename-virus.exe-to-photo.jpg case and is always a rejection.
        if (!detected.get().equals(file.getDeclaredContentType())) {
            log.warn("Content type mismatch for {}: declared {}, detected {}",
                file.getId(), file.getDeclaredContentType(), detected.get());
            reject(file, "File content does not match the declared type.");
            return false;
        }

        return true;
    }

    private boolean scanForViruses(MediaFile file, String quarantine) {
        try (InputStream content = objectStorage.openStream(quarantine, file.getObjectKey())) {
            VirusScanner.ScanResult result = virusScanner.scan(content);

            if (result.infected()) {
                log.warn("Infected upload {} rejected: {}", file.getId(), result.signature());
                reject(file, "File failed the security scan.");
                return false;
            }
            return true;
        } catch (java.io.IOException e) {
            throw new ScanUnavailableException("Could not read object for scanning", e);
        }
    }

    /**
     * Copy to the serving bucket, then mark READY. In that order: if the copy fails the file stays
     * unservable, whereas marking READY first would advertise a file that is not there yet.
     */
    private void promote(MediaFile file, String quarantine) {
        String serving = storageProperties.servingBucket();
        objectStorage.move(quarantine, file.getObjectKey(), serving, file.getObjectKey());

        file.markReady(serving, file.getObjectKey());
        mediaFileRepository.save(file);

        log.info("Media {} is ready ({}, {} bytes)",
            file.getId(), file.getDetectedContentType(), file.getSizeBytes());
    }

    /**
     * A rejected file's bytes are deleted, not left in quarantine. Keeping known-bad content around
     * means paying to store it and having somewhere for it to leak from; the row survives with the
     * reason, which is the part worth keeping.
     */
    private void reject(MediaFile file, String reason) {
        file.markQuarantined(reason);
        mediaFileRepository.save(file);

        try {
            objectStorage.delete(storageProperties.quarantineBucket(), file.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete rejected object for {}; it will be reaped later",
                file.getId(), e);
        }
    }
}
