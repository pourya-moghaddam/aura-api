package com.aura.media.download;

import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.media.file.MediaFile;
import com.aura.media.file.MediaFileRepository;
import com.aura.media.file.MediaStatus;
import com.aura.media.file.MediaVisibility;
import com.aura.media.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The only anonymous read path in this service, so the thing worth proving is what it
 * <em>refuses</em>.
 *
 * <p>Both gates are expressed in the repository query rather than as checks in the service, so
 * these tests pin the query arguments: if someone later widens the lookup to a bare
 * {@code findById} and filters afterwards, the mock stops matching and these fail. That is the
 * intended trip-wire — a filter in Java is one forgotten {@code if} away from serving a private or
 * unscanned file to the whole internet.
 */
@ExtendWith(MockitoExtension.class)
class PublicMediaServiceTest {

    private static final long OWNER = 42L;
    private static final String SERVING = "aura-media";
    private static final String KEY = "2026/08/12/abc";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ObjectStorage objectStorage;

    private PublicMediaService service;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        service = new PublicMediaService(mediaFileRepository, objectStorage);
        mediaId = UUID.randomUUID();
    }

    private MediaFile publishedAndReady() {
        MediaFile file = MediaFile.pending(mediaId, OWNER, SERVING, KEY, "image/png", "a.png");
        file.markReady(SERVING, KEY);
        file.publish();
        file.setDetectedContentType("image/png");
        file.setSizeBytes(1234L);
        file.setChecksumSha256("d0c3a1");
        return file;
    }

    @Test
    @DisplayName("a published, scanned file is served with its detected type and a strong ETag")
    void publishedFileIsServed() {
        InputStream bytes = new ByteArrayInputStream(new byte[] {1, 2, 3});
        when(mediaFileRepository.findByIdAndVisibilityAndStatus(
            mediaId, MediaVisibility.PUBLIC, MediaStatus.READY))
            .thenReturn(Optional.of(publishedAndReady()));
        when(objectStorage.openStream(SERVING, KEY)).thenReturn(bytes);

        PublicMediaService.PublicMedia media = service.open(mediaId);

        assertThat(media.contentType()).isEqualTo("image/png");
        assertThat(media.sizeBytes()).isEqualTo(1234L);
        assertThat(media.checksumSha256()).isEqualTo("d0c3a1");
        assertThat(media.body()).isNotNull();
    }

    @Test
    @DisplayName("the content type served is the detected one, never the caller's own claim")
    void servesDetectedTypeNotDeclared() {
        MediaFile file = publishedAndReady();
        // A caller who uploaded HTML while declaring it a PNG. Echoing the declaration back as a
        // Content-Type is how that gets sniffed and executed in someone else's browser.
        file.setDetectedContentType("text/plain");
        when(mediaFileRepository.findByIdAndVisibilityAndStatus(
            mediaId, MediaVisibility.PUBLIC, MediaStatus.READY))
            .thenReturn(Optional.of(file));
        when(objectStorage.openStream(SERVING, KEY))
            .thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThat(service.open(mediaId).contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("an unpublished file is a 404, indistinguishable from one that does not exist")
    void privateFileIsNotFound() {
        // The query itself excludes PRIVATE rows, so an unpublished file simply does not come back.
        when(mediaFileRepository.findByIdAndVisibilityAndStatus(
            mediaId, MediaVisibility.PUBLIC, MediaStatus.READY))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(mediaId))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(objectStorage, never()).openStream(anyString(), anyString());
    }

    @Test
    @DisplayName("no bytes are read from storage for a file that fails either gate")
    void refusalNeverTouchesStorage() {
        when(mediaFileRepository.findByIdAndVisibilityAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(mediaId))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(objectStorage, never()).openStream(anyString(), anyString());
        verify(objectStorage, never()).presignDownload(anyString(), anyString());
    }
}
