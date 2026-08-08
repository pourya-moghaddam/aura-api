package com.aura.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Object storage configuration.
 *
 * @param endpoint        where <em>this service</em> reaches storage. Inside Docker that is
 *                        {@code http://minio:9000}, a name only resolvable on the container network.
 * @param publicEndpoint  where <em>clients</em> reach storage, and therefore the host that presigned
 *                        URLs must carry. These differ almost everywhere: in Compose the internal
 *                        name is unresolvable from the browser, and in production the internal
 *                        address is typically a VPC endpoint while clients get a public or CDN
 *                        hostname. Signing with the internal one produces URLs that are valid but
 *                        unreachable — the upload fails with a DNS error rather than anything that
 *                        points at the cause. Defaults to {@code endpoint} for the single-host case.
 * @param region          required by the SDK's signing logic even when the backend ignores it
 * @param accessKey       credentials for the service itself, not for any end user
 * @param pathStyleAccess MinIO serves buckets as a path segment ({@code host/bucket/key}) rather
 *                        than as a subdomain ({@code bucket.host/key}). Virtual-host style needs
 *                        wildcard DNS, which does not exist for a container called "minio", so
 *                        this must stay true for MinIO and would flip to false on real S3.
 * @param quarantineBucket where uploads land first. Private, never served, and the only thing a
 *                         presigned upload URL can point at.
 * @param servingBucket    where objects are promoted after passing validation and scanning.
 * @param uploadUrlTtl     how long an issued upload URL stays usable. Short: it is a bearer
 *                         credential to write into your storage.
 * @param downloadUrlTtl   how long a download URL stays usable — rule 5's fifteen minutes.
 */
@ConfigurationProperties(prefix = "aura.media.storage")
public record StorageProperties(
    String endpoint,
    String publicEndpoint,
    @DefaultValue("us-east-1") String region,
    String accessKey,
    String secretKey,
    @DefaultValue("true") boolean pathStyleAccess,
    @DefaultValue("aura-quarantine") String quarantineBucket,
    @DefaultValue("aura-media") String servingBucket,
    @DefaultValue("15m") Duration uploadUrlTtl,
    @DefaultValue("15m") Duration downloadUrlTtl
) {

    public StorageProperties {
        // Single-host deployments where clients and this service share a view of storage.
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            publicEndpoint = endpoint;
        }
    }
}
