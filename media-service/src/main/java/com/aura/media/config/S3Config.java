package com.aura.media.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageProperties storageProperties;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(storageProperties.endpoint()))
            .region(Region.of(storageProperties.region()))
            .credentialsProvider(credentials())
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(storageProperties.pathStyleAccess())
                .build())
            .build();
    }

    /**
     * Separate from the client because presigning is a different operation: it produces a URL
     * offline by signing a request, without contacting storage at all.
     *
     * <p>Built against the <em>public</em> endpoint. The signature covers the host, so this cannot
     * be rewritten after the fact — signing with the internal address and swapping the hostname in
     * the string afterwards invalidates the signature. It has to be correct at signing time.
     */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
            .endpointOverride(URI.create(storageProperties.publicEndpoint()))
            .region(Region.of(storageProperties.region()))
            .credentialsProvider(credentials())
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(storageProperties.pathStyleAccess())
                .build())
            .build();
    }

    /**
     * Creates both buckets on startup if absent.
     *
     * <p>Convenience for local development, and harmless in production where they already exist —
     * the alternative is every fresh environment failing its first upload with a bucket-not-found
     * that looks like a code bug.
     *
     * <p>Neither bucket is made public. The serving bucket is still only reachable through signed
     * URLs; "serving" describes which lifecycle stage an object has reached, not that anyone can
     * fetch it by guessing a path.
     */
    @Bean
    public ApplicationRunner ensureBucketsExist(S3Client s3Client) {
        return args -> {
            ensureBucket(s3Client, storageProperties.quarantineBucket());
            ensureBucket(s3Client, storageProperties.servingBucket());
        };
    }

    private void ensureBucket(S3Client s3Client, String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.debug("Bucket {} already exists", bucket);
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created bucket {}", bucket);
            } catch (BucketAlreadyOwnedByYouException race) {
                // Two instances starting together; whoever lost is fine.
                log.debug("Bucket {} created concurrently", bucket);
            }
        }
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(storageProperties.accessKey(), storageProperties.secretKey()));
    }
}
