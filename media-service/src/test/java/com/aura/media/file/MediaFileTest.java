package com.aura.media.file;

import com.aura.media.upload.ObjectKeys;
import com.aura.media.scan.NoOpVirusScanner;
import com.aura.media.scan.VirusScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaFileTest {

    private MediaFile file() {
        return MediaFile.pending(
            UUID.randomUUID(), 1L, "aura-quarantine", "2026/08/12/abc", "image/png", "photo.png");
    }

    @Test
    @DisplayName("a new file starts unservable, in quarantine")
    void startsPending() {
        MediaFile file = file();

        assertThat(file.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(file.getStatus().isServable()).isFalse();
        assertThat(file.getBucket()).isEqualTo("aura-quarantine");
    }

    @Test
    @DisplayName("promotion moves the file's recorded location to the serving bucket")
    void markReadyMovesBucket() {
        // The row has to agree with where the bytes actually are, or download presigns the wrong
        // bucket and every fetch 404s.
        MediaFile file = file();

        file.markReady("aura-media", "2026/08/12/abc");

        assertThat(file.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(file.getBucket()).isEqualTo("aura-media");
        assertThat(file.getStatus().isServable()).isTrue();
    }

    @Test
    @DisplayName("a quarantined file keeps the reason and is never servable")
    void markQuarantinedRecordsReason() {
        MediaFile file = file();

        file.markQuarantined("File content does not match the declared type.");

        assertThat(file.getStatus()).isEqualTo(MediaStatus.QUARANTINED);
        assertThat(file.getFailureReason()).contains("does not match");
        assertThat(file.getStatus().isServable()).isFalse();
    }

    @Test
    @DisplayName("a failed file is not servable but is not a final verdict either")
    void markFailedIsRetryable() {
        MediaFile file = file();

        file.markFailed("Scanner unavailable.");

        assertThat(file.getStatus()).isEqualTo(MediaStatus.FAILED);
        assertThat(file.getStatus().isServable()).isFalse();
        assertThat(file.getStatus().isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = MediaStatus.class, names = {"PENDING", "UPLOADED", "SCANNING", "QUARANTINED", "FAILED"})
    @DisplayName("READY is the only servable state")
    void onlyReadyIsServable(MediaStatus status) {
        assertThat(status.isServable()).isFalse();
        assertThat(MediaStatus.READY.isServable()).isTrue();
    }

    @Test
    @DisplayName("terminal states are the two that are never reprocessed")
    void terminalStates() {
        assertThat(MediaStatus.READY.isTerminal()).isTrue();
        assertThat(MediaStatus.QUARANTINED.isTerminal()).isTrue();
        assertThat(MediaStatus.FAILED.isTerminal()).isFalse();
        assertThat(MediaStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("object keys are date-partitioned and carry no filename")
    void objectKeyShape() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        String key = ObjectKeys.forUpload(id, LocalDate.of(2026, 8, 5));

        assertThat(key).isEqualTo("2026/08/05/11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("variant keys hang off the base key")
    void variantKey() {
        assertThat(ObjectKeys.forVariant("2026/08/05/abc", "thumb")).isEqualTo("2026/08/05/abc/thumb");
    }

    @Test
    @DisplayName("the development scanner passes everything, which is why it warns")
    void noOpScannerPassesEverything() {
        VirusScanner.ScanResult result = new NoOpVirusScanner().scan(new ByteArrayInputStream(new byte[]{1}));

        assertThat(result.infected()).isFalse();
        assertThat(VirusScanner.ScanResult.infected("Eicar").infected()).isTrue();
        assertThat(VirusScanner.ScanResult.infected("Eicar").signature()).isEqualTo("Eicar");
    }
}
