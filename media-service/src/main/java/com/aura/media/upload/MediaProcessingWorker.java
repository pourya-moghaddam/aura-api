package com.aura.media.upload;

import com.aura.media.file.MediaFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives finalized uploads through validation.
 *
 * <p>Polling rather than an S3 event notification: MinIO can emit events, but that would make the
 * pipeline depend on a broker being reachable and on events not being lost, when the database
 * already knows exactly which rows are outstanding. The trade is a few seconds of latency on
 * something that is asynchronous by design anyway.
 *
 * <p>Also the recovery path. A file left mid-flight because this service died is picked up again
 * once its row ages past the stuck-work threshold — nothing has to remember to retry it.
 *
 * <p>Both calls below go through {@code MediaProcessingService}, a separate bean, rather than
 * private methods here. That is load-bearing: {@code @Transactional} is applied by a proxy, and a
 * call from one method of this class to another would bypass it entirely — leaving the claim
 * running without a transaction, where {@code SKIP LOCKED} silently stops providing any isolation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaProcessingWorker {

    private static final int BATCH_SIZE = 10;

    private final MediaProcessingService mediaProcessingService;

    @Scheduled(fixedDelayString = "${aura.media.processing.poll-interval:PT5S}")
    public void processPendingUploads() {
        List<MediaFile> batch = mediaProcessingService.claimBatch(BATCH_SIZE);

        for (MediaFile file : batch) {
            // Each file gets its own transaction inside process(). A failure on one must not
            // discard the verdicts already reached on the others in this batch.
            mediaProcessingService.process(file);
        }

        if (!batch.isEmpty()) {
            log.debug("Processed {} uploaded files", batch.size());
        }
    }
}
