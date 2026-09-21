package com.aura.media.upload;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.media.config.StorageProperties;
import com.aura.media.config.UploadPolicyProperties;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.storage.ObjectStorage;
import com.aura.media.upload.dto.UploadTicketRequest;
import com.aura.media.upload.dto.UploadTicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every limit is enforced here because this is the last moment the service is in the loop: once a
 * presigned URL is handed out it is a standalone write credential, and nothing downstream can take
 * it back.
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final long OWNER = 42L;
    private static final String QUARANTINE = "aura-quarantine";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private UploadPolicyProperties uploadPolicy;

    @Mock
    private UploadRateLimiter uploadRateLimiter;

    private UploadService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
            "http://minio:9000", "http://localhost:9000", "us-east-1", "key", "secret",
            true, QUARANTINE, "aura-media", Duration.ofMinutes(15), Duration.ofMinutes(15));

        service = new UploadService(
            mediaFileRepository, objectStorage, properties, uploadPolicy, uploadRateLimiter);
    }

    private void allow(String type, long maxSize) {
        when(uploadPolicy.isAllowed(type)).thenReturn(true);
        when(uploadPolicy.maxSizeFor(type)).thenReturn(maxSize);
    }

    @Test
    @DisplayName("an allowed type under the cap gets a ticket pointing at quarantine")
    void ticketIssued() {
        allow("image/png", 2_000_000L);
        when(objectStorage.presignUpload(eq(QUARANTINE), anyString(), eq("image/png")))
            .thenReturn("https://minio/presigned");

        UploadTicketResponse response =
            service.issueTicket(OWNER, new UploadTicketRequest("image/png", 1024L, "photo.png"));

        assertThat(response.uploadUrl()).isEqualTo("https://minio/presigned");
        // Content-Type is signed into the URL, so the client has to be told to send it back.
        assertThat(response.headers()).containsEntry("Content-Type", "image/png");
    }

    @Test
    @DisplayName("the row is written as PENDING in the quarantine bucket, never the serving one")
    void rowIsPendingInQuarantine() {
        // A presigned URL is a write credential; the only bucket it may point at is the one
        // nothing is served from.
        allow("image/png", 2_000_000L);
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);

        service.issueTicket(OWNER, new UploadTicketRequest("image/png", 1024L, "photo.png"));

        verify(mediaFileRepository).save(captor.capture());
        MediaFile saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(saved.getBucket()).isEqualTo(QUARANTINE);
        assertThat(saved.getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("the object key never contains the client's filename")
    void keyDoesNotContainFilename() {
        // Original names arrive with path separators in them; a key built from one is a
        // path-traversal question waiting to be asked.
        allow("image/png", 2_000_000L);
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);

        service.issueTicket(OWNER,
            new UploadTicketRequest("image/png", 1024L, "../../etc/passwd.png"));

        verify(mediaFileRepository).save(captor.capture());
        assertThat(captor.getValue().getObjectKey()).doesNotContain("passwd").doesNotContain("..");
    }

    @Test
    @DisplayName("a type outside the allowlist is refused")
    void disallowedTypeRefused() {
        when(uploadPolicy.isAllowed("application/x-msdownload")).thenReturn(false);

        assertThatThrownBy(() -> service.issueTicket(OWNER,
            new UploadTicketRequest("application/x-msdownload", 10L, "setup.exe")))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("cannot be uploaded");

        verify(objectStorage, never()).presignUpload(anyString(), anyString(), anyString());
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    @DisplayName("a declared size over the per-type cap is refused before a URL exists")
    void oversizeRefused() {
        allow("video/mp4", 50_000_000L);

        assertThatThrownBy(() -> service.issueTicket(OWNER,
            new UploadTicketRequest("video/mp4", 60_000_000L, "clip.mp4")))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("at most");

        verify(objectStorage, never()).presignUpload(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a size exactly on the cap is allowed")
    void sizeExactlyAtCapAllowed() {
        allow("image/png", 2_000_000L);

        service.issueTicket(OWNER, new UploadTicketRequest("image/png", 2_000_000L, "big.png"));

        verify(mediaFileRepository).save(any());
    }

    @Test
    @DisplayName("content type is matched case-insensitively and trimmed")
    void contentTypeNormalized() {
        allow("image/png", 2_000_000L);

        service.issueTicket(OWNER, new UploadTicketRequest("  IMAGE/PNG  ", 1024L, "photo.png"));

        verify(uploadPolicy).isAllowed("image/png");
    }

    @Test
    @DisplayName("the rate limit is consumed only after the file itself is judged acceptable")
    void rateLimitNotConsumedForRejectedTypes() {
        // Otherwise a client could burn another user's quota by spraying disallowed types.
        when(uploadPolicy.isAllowed(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.issueTicket(OWNER,
            new UploadTicketRequest("application/zip", 10L, "a.zip")))
            .isInstanceOf(BusinessRuleException.class);

        verify(uploadRateLimiter, never()).checkAndConsume(anyLong());
    }

    @Test
    @DisplayName("a rate-limited user gets no ticket")
    void rateLimitedUserRefused() {
        allow("image/png", 2_000_000L);
        doThrow(new com.aura.common.web.error.RateLimitExceededException("upload-rate-limit", "slow down"))
            .when(uploadRateLimiter).checkAndConsume(OWNER);

        assertThatThrownBy(() -> service.issueTicket(OWNER,
            new UploadTicketRequest("image/png", 1024L, "photo.png")))
            .isInstanceOf(com.aura.common.web.error.RateLimitExceededException.class);

        verify(objectStorage, never()).presignUpload(anyString(), anyString(), anyString());
    }
}
