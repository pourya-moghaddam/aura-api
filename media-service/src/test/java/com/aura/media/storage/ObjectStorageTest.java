package com.aura.media.storage;

import com.aura.media.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObjectStorageTest {

    private static final String QUARANTINE = "aura-quarantine";
    private static final String SERVING = "aura-media";
    private static final String KEY = "2026/08/12/abc";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
            "http://minio:9000", "http://localhost:9000", "us-east-1", "key", "secret",
            true, QUARANTINE, SERVING, Duration.ofMinutes(15), Duration.ofMinutes(10));
        storage = new ObjectStorage(s3Client, s3Presigner, properties);
    }

    @Test
    @DisplayName("an upload URL binds the bucket, key, content type and TTL into the signature")
    void presignUploadBindsEverything() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://minio/put").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        String url = storage.presignUpload(QUARANTINE, KEY, "image/png");

        ArgumentCaptor<PutObjectPresignRequest> captor =
            ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());

        assertThat(url).isEqualTo("https://minio/put");
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(captor.getValue().putObjectRequest().bucket()).isEqualTo(QUARANTINE);
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("a download URL uses the download TTL, not the upload one")
    void presignDownloadUsesItsOwnTtl() throws Exception {
        // The two are configured separately on purpose; reusing one duration for both means
        // changing the upload window silently changes how long download links live.
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://minio/get").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        storage.presignDownload(SERVING, KEY);

        ArgumentCaptor<GetObjectPresignRequest> captor =
            ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("head returns the object when it exists")
    void headFindsObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenReturn(HeadObjectResponse.builder().contentLength(1234L).build());

        assertThat(storage.head(QUARANTINE, KEY))
            .isPresent()
            .get()
            .extracting(HeadObjectResponse::contentLength)
            .isEqualTo(1234L);
    }

    @Test
    @DisplayName("a missing object is empty rather than an exception")
    void headMissingObjectIsEmpty() {
        // The caller uses this to detect "client called finalize without uploading", which is a
        // normal client mistake, not an exceptional condition.
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("nope").build());

        assertThat(storage.head(QUARANTINE, KEY)).isEmpty();
    }

    @Test
    @DisplayName("reading a prefix issues a ranged GET, not a full download")
    void readPrefixIsRanged() {
        // This is what keeps rule 1 intact: validating a 50MB video must not stream 50MB through
        // this process.
        byte[] header = {(byte) 0x89, 'P', 'N', 'G'};
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
            new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(header))));

        byte[] read = storage.readPrefix(QUARANTINE, KEY, 4);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());

        assertThat(read).isEqualTo(header);
        assertThat(captor.getValue().range()).isEqualTo("bytes=0-3");
    }

    @Test
    @DisplayName("a read failure becomes a StorageException rather than a raw IOException")
    void readPrefixWrapsFailures() {
        java.io.InputStream failing = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("connection reset");
            }
        };
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
            new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(failing)));

        assertThatThrownBy(() -> storage.readPrefix(QUARANTINE, KEY, 4))
            .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("promotion copies before deleting, since storage has no atomic move")
    void moveCopiesThenDeletes() {
        storage.move(QUARANTINE, KEY, SERVING, KEY);

        var inOrder = inOrder(s3Client);
        inOrder.verify(s3Client).copyObject(any(CopyObjectRequest.class));
        inOrder.verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("a failed source delete does not fail the promotion")
    void moveToleratesDeleteFailure() {
        // The copy already succeeded, so the file is servable. Throwing here would tell the caller
        // the promotion failed and leave a READY-able object stranded - a leftover quarantine
        // object is merely wasted space by comparison.
        doThrow(S3Exception.builder().message("denied").build())
            .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        storage.move(QUARANTINE, KEY, SERVING, KEY);

        verify(s3Client).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    @DisplayName("copy targets the destination bucket and key it was given")
    void moveTargetsTheRightPlace() {
        storage.move(QUARANTINE, "src", SERVING, "dst");

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());

        assertThat(captor.getValue().sourceBucket()).isEqualTo(QUARANTINE);
        assertThat(captor.getValue().sourceKey()).isEqualTo("src");
        assertThat(captor.getValue().destinationBucket()).isEqualTo(SERVING);
        assertThat(captor.getValue().destinationKey()).isEqualTo("dst");
    }

    @Test
    @DisplayName("openStream fetches the whole object, for the scanner only")
    void openStreamIsUnranged() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
            new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1}))));

        storage.openStream(QUARANTINE, KEY);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().range()).isNull();
    }

    @Test
    @DisplayName("put writes the given bytes with the given content type")
    void putWritesBytes() {
        storage.put(SERVING, KEY, new byte[]{1, 2, 3}, "image/png");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("delete targets the bucket and key given")
    void deleteTargetsTheRightObject() {
        storage.delete(QUARANTINE, KEY);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(QUARANTINE);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }
}
