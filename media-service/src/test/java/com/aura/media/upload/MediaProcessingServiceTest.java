package com.aura.media.upload;

import com.aura.media.config.StorageProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.scan.ScanUnavailableException;
import com.aura.media.scan.VirusScanner;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.validation.ContentTypeDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The quarantine → validate → scan → promote gate.
 *
 * <p>This is the security boundary of the whole service, and every one of its failure modes is
 * silent: a file promoted without validation looks completely normal until someone downloads it.
 * The cases below are the ones where "it still returned 200" and "it did the right thing" come
 * apart.
 */
@ExtendWith(MockitoExtension.class)
class MediaProcessingServiceTest {

    private static final String QUARANTINE = "aura-quarantine";
    private static final String SERVING = "aura-media";
    private static final String KEY = "2026/08/12/abc";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private ContentTypeDetector contentTypeDetector;

    @Mock
    private VirusScanner virusScanner;

    private MediaProcessingService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
            "http://minio:9000", "http://localhost:9000", "us-east-1", "key", "secret",
            true, QUARANTINE, SERVING, Duration.ofMinutes(15), Duration.ofMinutes(15));

        service = new MediaProcessingService(
            mediaFileRepository, objectStorage, contentTypeDetector, virusScanner, properties);
    }

    private MediaFile file() {
        MediaFile file = MediaFile.pending(
            UUID.randomUUID(), 1L, QUARANTINE, KEY, "image/png", "photo.png");
        file.setStatus(MediaStatus.SCANNING);
        return file;
    }

    private void objectExists(long size) {
        when(objectStorage.head(QUARANTINE, KEY))
            .thenReturn(Optional.of(HeadObjectResponse.builder().contentLength(size).build()));
    }

    private void bytesDetectAs(String contentType) {
        when(objectStorage.readPrefix(eq(QUARANTINE), eq(KEY), anyInt())).thenReturn(new byte[]{1, 2});
        when(contentTypeDetector.detect(any())).thenReturn(Optional.of(contentType));
    }

    @Nested
    class HappyPath {

        @Test
        @DisplayName("a file whose bytes match its declared type is scanned and promoted")
        void validFilePromoted() {
            MediaFile file = file();
            objectExists(2048L);
            bytesDetectAs("image/png");
            when(objectStorage.openStream(QUARANTINE, KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
            when(virusScanner.scan(any())).thenReturn(VirusScanner.ScanResult.clean());

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.READY);
            assertThat(file.getSizeBytes()).isEqualTo(2048L);
            assertThat(file.getBucket()).isEqualTo(SERVING);
            verify(objectStorage).move(QUARANTINE, KEY, SERVING, KEY);
        }

        @Test
        @DisplayName("the object is copied before the row is marked ready, never the other way round")
        void moveHappensBeforeReady() {
            // Marking READY first would advertise a servable file that is not in the serving
            // bucket yet - a 404 for anyone quick enough to ask.
            MediaFile file = file();
            objectExists(10L);
            bytesDetectAs("image/png");
            when(objectStorage.openStream(QUARANTINE, KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
            when(virusScanner.scan(any())).thenReturn(VirusScanner.ScanResult.clean());

            service.process(file);

            var inOrder = inOrder(objectStorage, mediaFileRepository);
            inOrder.verify(objectStorage).move(anyString(), anyString(), anyString(), anyString());
            inOrder.verify(mediaFileRepository).save(file);
        }
    }

    @Nested
    class Rejections {

        @Test
        @DisplayName("finalize without an actual upload does not produce a phantom READY row")
        void missingObjectRejected() {
            MediaFile file = file();
            when(objectStorage.head(QUARANTINE, KEY)).thenReturn(Optional.empty());

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
            verify(objectStorage, never()).move(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("an executable renamed .png is rejected on the mismatch, not the extension")
        void declaredTypeMismatchRejected() {
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("application/x-dosexec");

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
            assertThat(file.getFailureReason()).contains("does not match the declared type");
            verify(virusScanner, never()).scan(any());
            verify(objectStorage, never()).move(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("bytes matching no known format are rejected")
        void unrecognisedContentRejected() {
            MediaFile file = file();
            objectExists(1024L);
            when(objectStorage.readPrefix(eq(QUARANTINE), eq(KEY), anyInt())).thenReturn(new byte[]{9, 9});
            when(contentTypeDetector.detect(any())).thenReturn(Optional.empty());

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
            verify(virusScanner, never()).scan(any());
        }

        @Test
        @DisplayName("an infected file is rejected even though its type is correct")
        void infectedFileRejected() {
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("image/png");
            when(objectStorage.openStream(QUARANTINE, KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
            when(virusScanner.scan(any())).thenReturn(VirusScanner.ScanResult.infected("Eicar-Test"));

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
            verify(objectStorage, never()).move(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a rejected file's bytes are deleted from quarantine")
        void rejectedBytesDeleted() {
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("application/x-dosexec");

            service.process(file);

            verify(objectStorage).delete(QUARANTINE, KEY);
        }

        @Test
        @DisplayName("a failure to delete rejected bytes still leaves the verdict recorded")
        void deleteFailureDoesNotUndoTheVerdict() {
            // The row carrying QUARANTINED is what keeps the file unservable. Losing that because
            // the storage delete failed would make a rejected file downloadable.
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("application/x-dosexec");
            doThrow(new RuntimeException("storage down")).when(objectStorage).delete(QUARANTINE, KEY);

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
        }

        @Test
        @DisplayName("content validation runs before the virus scan, because it is far cheaper")
        void cheapCheckRunsFirst() {
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("application/x-dosexec");

            service.process(file);

            // Sixteen bytes read versus a whole video streamed through clamd.
            verify(objectStorage).readPrefix(eq(QUARANTINE), eq(KEY), anyInt());
            verify(objectStorage, never()).openStream(anyString(), anyString());
        }
    }

    @Nested
    class InfrastructureFailures {

        @Test
        @DisplayName("a scanner outage is FAILED, not QUARANTINED — the file was never judged")
        void scannerOutageIsRetryable() {
            // QUARANTINED is permanent and never retried. Recording an infrastructure problem as a
            // verdict about the file would silently discard a legitimate upload for good.
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("image/png");
            when(objectStorage.openStream(QUARANTINE, KEY)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
            when(virusScanner.scan(any())).thenThrow(new ScanUnavailableException("clamd unreachable", null));

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.FAILED);
            assertThat(MediaStatus.FAILED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("an unexpected error is FAILED and does not promote the file")
        void unexpectedErrorIsFailed() {
            MediaFile file = file();
            objectExists(1024L);
            bytesDetectAs("image/png");
            when(objectStorage.openStream(QUARANTINE, KEY)).thenThrow(new RuntimeException("boom"));

            service.process(file);

            assertThat(file.getStatus()).isEqualTo(MediaStatus.FAILED);
            verify(objectStorage, never()).move(anyString(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    class Claiming {

        @Test
        @DisplayName("claiming moves rows out of UPLOADED so the next poll cannot re-claim them")
        void claimMarksScanning() {
            // FOR UPDATE SKIP LOCKED only holds until commit. Without the status change, a second
            // instance polling immediately afterwards picks up work already in flight and two
            // workers process the same file.
            MediaFile a = MediaFile.pending(UUID.randomUUID(), 1L, QUARANTINE, KEY, "image/png", "a.png");
            MediaFile b = MediaFile.pending(UUID.randomUUID(), 1L, QUARANTINE, KEY, "image/png", "b.png");
            a.setStatus(MediaStatus.UPLOADED);
            b.setStatus(MediaStatus.UPLOADED);

            when(mediaFileRepository.claimUploadedForProcessing(10)).thenReturn(List.of(a, b));
            when(mediaFileRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

            List<MediaFile> claimed = service.claimBatch(10);

            assertThat(claimed).extracting(MediaFile::getStatus)
                .containsOnly(MediaStatus.SCANNING);
        }

        @Test
        @DisplayName("an empty queue claims nothing")
        void emptyClaim() {
            when(mediaFileRepository.claimUploadedForProcessing(10)).thenReturn(List.of());
            when(mediaFileRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(service.claimBatch(10)).isEmpty();
        }
    }
}
