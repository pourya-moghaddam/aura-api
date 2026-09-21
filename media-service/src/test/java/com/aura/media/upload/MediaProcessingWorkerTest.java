package com.aura.media.upload;

import com.aura.media.file.MediaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * The worker delegates to a separate bean on purpose. {@code @Transactional} is applied by a proxy,
 * so a self-invocation would bypass it silently — leaving {@code FOR UPDATE SKIP LOCKED} running
 * outside a transaction, where it provides no isolation at all and two instances happily process
 * the same file. That has already happened once here.
 */
@ExtendWith(MockitoExtension.class)
class MediaProcessingWorkerTest {

    @Mock
    private MediaProcessingService mediaProcessingService;

    @InjectMocks
    private MediaProcessingWorker worker;

    private MediaFile file() {
        return MediaFile.pending(UUID.randomUUID(), 1L, "q", "k", "image/png", "a.png");
    }

    @Test
    @DisplayName("every claimed file is processed")
    void processesTheWholeBatch() {
        MediaFile a = file();
        MediaFile b = file();
        when(mediaProcessingService.claimBatch(anyInt())).thenReturn(List.of(a, b));

        worker.processPendingUploads();

        verify(mediaProcessingService).process(a);
        verify(mediaProcessingService).process(b);
    }

    @Test
    @DisplayName("claiming goes through the service bean, so the proxy actually applies")
    void claimIsDelegatedNotSelfInvoked() {
        when(mediaProcessingService.claimBatch(anyInt())).thenReturn(List.of());

        worker.processPendingUploads();

        verify(mediaProcessingService).claimBatch(anyInt());
    }

    @Test
    @DisplayName("an empty queue processes nothing")
    void emptyBatchDoesNothing() {
        when(mediaProcessingService.claimBatch(anyInt())).thenReturn(List.of());

        worker.processPendingUploads();

        verify(mediaProcessingService, never()).process(any());
    }
}
