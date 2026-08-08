package com.aura.media.validation;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Identifies a file from its leading bytes — rule 2.
 *
 * <p>The extension and the declared Content-Type are both attacker-controlled. Renaming
 * {@code virus.exe} to {@code photo.jpg} and declaring {@code image/jpeg} costs nothing; only the
 * bytes are hard to fake, because they have to still be a working file of the claimed type
 * afterwards.
 *
 * <p>Hand-rolled rather than pulled from Tika. Tika is excellent and enormous — it detects a
 * thousand formats by loading a parser stack — and this service accepts four. A 30-line signature
 * table that is obvious on inspection is a better fit than a dependency whose behaviour on
 * adversarial input is its own research project.
 */
@Component
public class ContentTypeDetector {

    /** Enough for every signature below. MP4's ftyp box sits at offset 4 and runs to 12. */
    public static final int PREFIX_BYTES = 16;

    private record Signature(String contentType, int offset, byte[] magic) {

        boolean matches(byte[] prefix) {
            if (prefix.length < offset + magic.length) {
                return false;
            }
            return Arrays.equals(prefix, offset, offset + magic.length, magic, 0, magic.length);
        }
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};          // "RIFF"
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};          // "WEBP" at offset 8
    private static final byte[] FTYP = {0x66, 0x74, 0x79, 0x70};          // "ftyp" at offset 4

    private static final Signature[] SIGNATURES = {
        new Signature("image/jpeg", 0, JPEG),
        new Signature("image/png", 0, PNG),
        // MP4 and friends: a box-length field first, then "ftyp". The brand that follows
        // distinguishes mp4 from mov and 3gp, but all of them are containers we either accept or
        // do not, so the box marker alone is the decision point.
        new Signature("video/mp4", 4, FTYP),
    };

    /**
     * @return the detected type, or empty when the bytes match nothing known — which is itself a
     *         rejection, not an "allow through unrecognised"
     */
    public Optional<String> detect(byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return Optional.empty();
        }

        // WebP is a two-part signature: a RIFF container whose form type is WEBP. Checking only
        // RIFF would also match .wav and .avi, so both halves have to hold.
        if (new Signature("", 0, RIFF).matches(prefix) && new Signature("", 8, WEBP).matches(prefix)) {
            return Optional.of("image/webp");
        }

        return Arrays.stream(SIGNATURES)
            .filter(signature -> signature.matches(prefix))
            .map(Signature::contentType)
            .findFirst();
    }
}
