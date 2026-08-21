package com.aura.media.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

    /**
     * Scoped by owner, never a bare {@code findById}. Same reasoning as the address book: resolving
     * by id alone and checking ownership afterwards is the shape that becomes an IDOR the first
     * time someone forgets the second half.
     */
    Optional<MediaFile> findByIdAndOwnerId(UUID id, Long ownerId);

    List<MediaFile> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * The one lookup that is deliberately <em>not</em> owner-scoped, because its caller is an
     * anonymous shopper looking at a product page.
     *
     * <p>Both gates are in the query rather than checked afterwards in Java. That is the whole
     * safety argument for this method existing next to {@link #findByIdAndOwnerId}: a reviewer can
     * see that no row outside {@code PUBLIC + READY} can come back from it at all, so there is no
     * path where a forgotten {@code if} exposes a private or unscanned file.
     */
    Optional<MediaFile> findByIdAndVisibilityAndStatus(
        UUID id, MediaVisibility visibility, MediaStatus status);

    /**
     * Work waiting to be picked up. {@code SKIP LOCKED} so more than one instance can run the
     * worker without both grabbing the same row and scanning it twice.
     */
    @Query(value = """
        SELECT * FROM media_files
        WHERE status = 'UPLOADED'
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<MediaFile> claimUploadedForProcessing(@Param("batchSize") int batchSize);

    /**
     * Uploads that were issued a URL and never completed. Both the row and the orphaned object (if
     * the client uploaded but never called finalize) need reaping — otherwise every abandoned
     * upload is paid for twice, in table rows and in storage nobody will ever claim.
     */
    List<MediaFile> findByStatusAndCreatedAtBefore(MediaStatus status, OffsetDateTime cutoff);
}
