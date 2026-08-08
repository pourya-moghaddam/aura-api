package com.aura.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * What may be uploaded, how large, and how often — rule 7.
 *
 * <p>Limits are enforced when the presigned URL is <em>issued</em>, because that is the last moment
 * this service is in the loop. Once a client holds the URL it talks to storage directly, so a check
 * placed anywhere later would be checking something that has already happened.
 *
 * @param allowedContentTypes declared types this service will issue an upload URL for at all. An
 *                            allowlist rather than a blocklist: the set of dangerous types is
 *                            open-ended and grows, the set we actually serve is small and known.
 * @param maxSizeBytesByType  per-type ceilings. A product photo and a product video have wildly
 *                            different reasonable sizes, and one global limit would have to be the
 *                            larger of the two — which then applies to images as well.
 * @param defaultMaxSizeBytes fallback for an allowed type with no explicit entry
 * @param uploadsPerHour      per-user issuance rate. Bounds how fast one account can fill storage
 *                            even while staying inside every per-file limit.
 */
@ConfigurationProperties(prefix = "aura.media.upload")
public record UploadPolicyProperties(
    @DefaultValue({"image/jpeg", "image/png", "image/webp", "video/mp4"})
    List<String> allowedContentTypes,

    Map<String, Long> maxSizeBytesByType,

    @DefaultValue("10485760") long defaultMaxSizeBytes,

    @DefaultValue("60") int uploadsPerHour
) {

    public UploadPolicyProperties {
        if (maxSizeBytesByType == null) {
            maxSizeBytesByType = Map.of();
        }
    }

    public boolean isAllowed(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType.toLowerCase());
    }

    public long maxSizeFor(String contentType) {
        return maxSizeBytesByType.getOrDefault(contentType, defaultMaxSizeBytes);
    }
}
