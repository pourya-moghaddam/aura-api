package com.aura.media.upload;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaQueryServiceTest {

    private static final long OWNER = 42L;
    private static final long OTHER_OWNER = 99L;
    private static final String QUARANTINE = "aura-quarantine";
    private static final String KEY = "2026/08/12/abc";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ObjectStorage objectStorage;

    private MediaQueryService service;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
            "http://minio:9000", null, "us-east-1", "key", "secret",
            true, QUARANTINE, "aura-media", Duration.ofMinutes(15), Duration.ofMinutes(15));
        service = new MediaQueryService(mediaFileRepository, objectStorage, properties);
        mediaId = UUID.randomUUID();
    }

    private MediaFile file(MediaStatus status) {
        MediaFile file = MediaFile.pending(mediaId, OWNER, QUARANTINE, KEY, "image/png", "a.png");
        file.setStatus(status);
        return file;
    }

    private void owned(MediaFile file) {
        when(mediaFileRepository.findByIdAndOwnerId(mediaId, OWNER)).thenReturn(Optional.of(file));
    }

    @Test
    @DisplayName("another user's media is a 404, not a 403 — it does not exist as far as they know")
    void otherUsersMediaIsNotFound() {
        when(mediaFileRepository.findByIdAndOwnerId(mediaId, OTHER_OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OTHER_OWNER, mediaId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("finalize moves a pending upload into the worker's queue")
    void finalizeMarksUploaded() {
        MediaFile file = file(MediaStatus.PENDING);
        owned(file);

        service.markUploaded(OWNER, mediaId);

        assertThat(file.getStatus()).isEqualTo(MediaStatus.UPLOADED);
        verify(mediaFileRepository).save(file);
    }

    @ParameterizedTest
    @EnumSource(value = MediaStatus.class, names = {"UPLOADED", "SCANNING", "READY", "QUARANTINED"})
    @DisplayName("a repeated finalize never resets a file that has moved on")
    void finalizeIsIdempotent(MediaStatus status) {
        // Clients retry. Pushing an already-validated file back into the queue would re-run the
        // scan on something already promoted - and pushing back a QUARANTINED one would give a
        // rejected file another chance at promotion.
        MediaFile file = file(status);
        owned(file);

        service.markUploaded(OWNER, mediaId);

        assertThat(file.getStatus()).isEqualTo(status);
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleting mid-scan is refused, or the worker promotes a row that vanished")
    void deleteDuringScanRefused() {
        owned(file(MediaStatus.SCANNING));

        assertThatThrownBy(() -> service.delete(OWNER, mediaId))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("still being processed");

        verify(mediaFileRepository, never()).delete(any());
    }

    @Test
    @DisplayName("the row goes first, so a storage failure leaves an orphan object not a broken row")
    void rowDeletedBeforeObject() {
        MediaFile file = file(MediaStatus.READY);
        owned(file);

        service.delete(OWNER, mediaId);

        var inOrder = inOrder(mediaFileRepository, objectStorage);
        inOrder.verify(mediaFileRepository).delete(file);
        inOrder.verify(objectStorage).delete(QUARANTINE, KEY);
    }

    @Test
    @DisplayName("a storage failure during delete does not resurrect the row")
    void storageFailureDoesNotFailTheDelete() {
        MediaFile file = file(MediaStatus.READY);
        owned(file);
        doThrow(new RuntimeException("storage down")).when(objectStorage).delete(anyString(), anyString());

        service.delete(OWNER, mediaId);

        verify(mediaFileRepository).delete(file);
    }

    @Test
    @DisplayName("listing is scoped to the owner")
    void listScopedToOwner() {
        when(mediaFileRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER))
            .thenReturn(List.of(file(MediaStatus.READY)));

        assertThat(service.listFor(OWNER)).hasSize(1);
        verify(mediaFileRepository).findByOwnerIdOrderByCreatedAtDesc(OWNER);
    }
}
