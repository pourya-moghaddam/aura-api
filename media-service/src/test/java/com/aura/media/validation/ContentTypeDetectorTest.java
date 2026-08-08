package com.aura.media.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTypeDetectorTest {

    private final ContentTypeDetector detector = new ContentTypeDetector();

    private byte[] prefix(int... bytes) {
        byte[] result = new byte[ContentTypeDetector.PREFIX_BYTES];
        for (int i = 0; i < bytes.length && i < result.length; i++) {
            result[i] = (byte) bytes[i];
        }
        return result;
    }

    @Test
    void detectsJpeg() {
        assertThat(detector.detect(prefix(0xFF, 0xD8, 0xFF, 0xE0))).contains("image/jpeg");
    }

    @Test
    void detectsPng() {
        assertThat(detector.detect(prefix(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
            .contains("image/png");
    }

    @Test
    void detectsWebp() {
        // "RIFF" ---- "WEBP"
        assertThat(detector.detect(prefix(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)))
            .contains("image/webp");
    }

    /**
     * WebP's signature is RIFF plus a WEBP form type. A WAV file is also RIFF — checking only the
     * container would wave it through as an image.
     */
    @Test
    void doesNotMistakeAWavForAWebp() {
        // "RIFF" ---- "WAVE"
        assertThat(detector.detect(prefix(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45)))
            .isEmpty();
    }

    @Test
    void detectsMp4ByItsFtypBox() {
        // 4-byte box length, then "ftyp"
        assertThat(detector.detect(prefix(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70)))
            .contains("video/mp4");
    }

    /** The attack rule 2 exists for: an executable renamed to photo.jpg. */
    @Test
    void rejectsAWindowsExecutableWhateverItIsCalled() {
        // "MZ" - the DOS header every PE binary starts with
        assertThat(detector.detect(prefix(0x4D, 0x5A, 0x90, 0x00))).isEmpty();
    }

    @Test
    void rejectsAnElfBinary() {
        assertThat(detector.detect(prefix(0x7F, 0x45, 0x4C, 0x46))).isEmpty();
    }

    @Test
    void rejectsAShellScript() {
        // "#!/b"
        assertThat(detector.detect(prefix(0x23, 0x21, 0x2F, 0x62))).isEmpty();
    }

    /** Unrecognised is a rejection, not a shrug. */
    @Test
    void unknownBytesAreNotDetected() {
        assertThat(detector.detect(prefix(0x01, 0x02, 0x03, 0x04))).isEmpty();
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(detector.detect(new byte[0])).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }

    /** A truncated file must not match on a partial signature. */
    @Test
    void doesNotMatchOnATruncatedPrefix() {
        assertThat(detector.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }
}
