package com.aura.media.download;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.media.config.StorageProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * If anything short of READY were downloadable, the entire scan gate would be decorative — a
 * quarantined file that can still be fetched has not been quarantined.
 */
@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    private static final long OWNER = 42L;
    private static final String SERVING = "aura-media";
    private static final String KEY = "2026/08/12/abc";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ObjectStorage objectStorage;

    private DownloadService service;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
            "http://minio:9000", "http://localhost:9000", "us-east-1", "key", "secret",
            true, "aura-quarantine", SERVING, Duration.ofMinutes(15), Duration.ofMinutes(15));
        service = new DownloadService(mediaFileRepository, objectStorage, properties);
        mediaId = UUID.randomUUID();
    }

    private MediaFile file(MediaStatus status) {
        MediaFile file = MediaFile.pending(mediaId, OWNER, SERVING, KEY, "image/png", "a.png");
        file.setStatus(status);
        return file;
    }

    @Test
    @DisplayName("a READY file gets a presigned URL with an expiry")
    void readyFileGetsUrl() {
        when(mediaFileRepository.findByIdAndOwnerId(mediaId, OWNER))
            .thenReturn(Optional.of(file(MediaStatus.READY)));
        when(objectStorage.presignDownload(SERVING, KEY)).thenReturn("https://minio/signed");

        DownloadService.DownloadUrlResponse response = service.issueDownloadUrl(OWNER, mediaId);

        assertThat(response.url()).isEqualTo("https://minio/signed");
        assertThat(response.expiresAt()).isAfter(java.time.Instant.now());
    }

    @ParameterizedTest
    @EnumSource(value = MediaStatus.class, names = {"PENDING", "UPLOADED", "SCANNING", "QUARANTINED", "FAILED"})
    @DisplayName("nothing short of READY is downloadable, quarantined content least of all")
    void nonReadyStatesRefused(MediaStatus status) {
        when(mediaFileRepository.findByIdAndOwnerId(mediaId, OWNER))
            .thenReturn(Optional.of(file(status)));

        assertThatThrownBy(() -> service.issueDownloadUrl(OWNER, mediaId))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not available");

        verify(objectStorage, never()).presignDownload(anyString(), anyString());
    }

    @Test
    @DisplayName("another user's file is a 404 and no URL is minted")
    void otherUsersFileRefused() {
        when(mediaFileRepository.findByIdAndOwnerId(mediaId, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueDownloadUrl(99L, mediaId))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(objectStorage, never()).presignDownload(anyString(), anyString());
    }
}
