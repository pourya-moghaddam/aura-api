-- Media metadata. The bytes live in object storage; this table owns everything else — who may
-- read a file, whether it has cleared validation, and where it currently sits.

CREATE TABLE media_files
(
    -- UUID rather than a sequence, because this id appears in object keys and in URLs handed to
    -- clients. A sequential id would let anyone who uploads two files infer how many exist and
    -- probe for other people's, which the permission check would then have to be the only thing
    -- standing in the way of.
    id                    UUID PRIMARY KEY,

    -- The uploading user, taken from the token subject and never from the request body.
    owner_id              BIGINT       NOT NULL,

    -- What the client claimed. Kept alongside the detected type rather than replaced by it, so a
    -- mismatch is inspectable after the fact instead of silently overwritten.
    original_filename     VARCHAR(255),
    declared_content_type VARCHAR(100) NOT NULL,
    -- What the file's leading bytes actually say it is. Null until finalize runs.
    detected_content_type VARCHAR(100),

    size_bytes            BIGINT,
    -- VARCHAR, not CHAR. Postgres pads CHAR(n) with spaces on read, so a comparison against an
    -- unpadded digest silently fails; it is also what Hibernate expects for a plain length-bounded
    -- String, and ddl-auto=validate rejects the mismatch outright.
    checksum_sha256       VARCHAR(64),

    -- Current location. Objects move quarantine -> serving on passing validation, so the bucket
    -- is state, not a constant.
    bucket                VARCHAR(63)  NOT NULL,
    object_key            TEXT         NOT NULL,

    /*
     * PENDING     - presigned URL issued, bytes not yet confirmed to exist
     * UPLOADED    - client called finalize, object is present in quarantine
     * SCANNING    - content validation and virus scan in flight
     * READY       - passed everything, promoted to the serving bucket, downloadable
     * QUARANTINED - failed content validation or virus scan; never servable
     * FAILED      - infrastructure error during processing; retryable
     *
     * Only READY is downloadable. That is the gate between upload and access.
     */
    status                VARCHAR(20)  NOT NULL,
    failure_reason        TEXT,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ready_at              TIMESTAMPTZ,

    CONSTRAINT ck_media_files_status
        CHECK (status IN ('PENDING', 'UPLOADED', 'SCANNING', 'READY', 'QUARANTINED', 'FAILED'))
);

CREATE INDEX idx_media_files_owner ON media_files (owner_id);

-- The worker only ever scans rows waiting for processing, and this keeps that scan cheap once the
-- table is mostly READY rows that will never be looked at again by it.
CREATE INDEX idx_media_files_pending_work ON media_files (created_at)
    WHERE status IN ('UPLOADED', 'SCANNING');

-- Finds abandoned uploads: a presigned URL was issued and the client never followed through.
-- Those rows and their orphaned objects need reaping, or every abandoned upload is paid for twice
-- (a dead row here, and bytes in the quarantine bucket nobody will ever claim).
CREATE INDEX idx_media_files_stale_pending ON media_files (created_at)
    WHERE status = 'PENDING';

-- Derived files: thumbnails and compressed variants, produced asynchronously after the original
-- is READY. Separate from media_files because they have no independent lifecycle - they are not
-- uploaded, not scanned, and they die with their parent.
CREATE TABLE media_variants
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    media_file_id UUID         NOT NULL REFERENCES media_files (id) ON DELETE CASCADE,
    -- e.g. 'thumbnail', 'medium', 'webp'
    variant       VARCHAR(50)  NOT NULL,
    object_key    TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    width         INT,
    height        INT,
    size_bytes    BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_media_variants UNIQUE (media_file_id, variant)
);

CREATE INDEX idx_media_variants_file ON media_variants (media_file_id);
