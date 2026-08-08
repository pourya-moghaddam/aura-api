package com.aura.media.storage;

import com.aura.media.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Everything this service does to object storage, in one place.
 *
 * <p>Note what is absent: there is no "upload" method taking a stream of user bytes. Uploads happen
 * between the client and storage directly via a presigned URL — rule 1 — so the only bytes this
 * service ever reads are the handful it needs for content validation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    /**
     * A time-limited credential permitting exactly one PUT to exactly one key.
     *
     * <p>The content type is bound into the signature, so a client that asked to upload a JPEG
     * cannot use the same URL to write a different type. That is a useful constraint but not a
     * security control on its own — it binds what the client <em>declared</em>, and declarations
     * are exactly what content validation exists to distrust.
     */
    public String presignUpload(String bucket, String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(storageProperties.uploadUrlTtl())
            .putObjectRequest(put)
            .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    /** A time-limited credential permitting one GET. Rule 5: clients never see a raw path. */
    public String presignDownload(String bucket, String objectKey) {
        GetObjectRequest get = GetObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(storageProperties.downloadUrlTtl())
            .getObjectRequest(get)
            .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /** Empty when the object is not there — used to confirm a client actually uploaded. */
    public Optional<HeadObjectResponse> head(String bucket, String objectKey) {
        try {
            return Optional.of(s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads only the leading bytes of an object.
     *
     * <p>This is what makes server-side content validation possible without violating rule 1: a
     * ranged GET pulls the first few bytes rather than streaming a 500MB video through this process
     * to look at its header.
     */
    public byte[] readPrefix(String bucket, String objectKey, int byteCount) {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .range("bytes=0-" + (byteCount - 1))
            .build();

        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            return response.readAllBytes();
        } catch (IOException e) {
            throw new StorageException("Could not read object prefix for " + objectKey, e);
        }
    }

    /** Full stream. Only for the virus scanner, which genuinely has to see every byte. */
    public InputStream openStream(String bucket, String objectKey) {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    public void put(String bucket, String objectKey, byte[] content, String contentType) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build(),
            RequestBody.fromBytes(content));
    }

    /**
     * Promotion from quarantine to serving is a server-side copy followed by a delete: storage has
     * no atomic move. The delete is best-effort — a leftover quarantine object is wasted space,
     * whereas failing the promotion after the copy succeeded would leave a file the user was told
     * was ready but cannot fetch.
     */
    public void move(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
            .sourceBucket(sourceBucket)
            .sourceKey(sourceKey)
            .destinationBucket(targetBucket)
            .destinationKey(targetKey)
            .build());

        try {
            delete(sourceBucket, sourceKey);
        } catch (RuntimeException e) {
            log.warn("Copied {}/{} to {}/{} but could not delete the source; it will be reaped later",
                sourceBucket, sourceKey, targetBucket, targetKey, e);
        }
    }

    public void delete(String bucket, String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }
}
