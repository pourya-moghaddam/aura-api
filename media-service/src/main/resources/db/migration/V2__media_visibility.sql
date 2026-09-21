-- Who may read a file, as opposed to whether it is readable at all (which is `status`).
--
-- Added because the storefront has no way to show a product photo otherwise: a shopper browsing
-- the catalogue is anonymous by design, and every read path in this service was owner-scoped.
-- Rather than loosening the owner check, a file attached to something already public is marked
-- public explicitly, and a separate anonymous endpoint serves only files in that state.

ALTER TABLE media_files
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';

ALTER TABLE media_files
    ADD CONSTRAINT ck_media_files_visibility
        CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

-- The DEFAULT above backfills existing rows to PRIVATE, which is the safe direction: nothing that
-- was owner-only yesterday becomes world-readable because of this migration. Product images
-- uploaded before this change stay unreadable until re-attached, which is correct — we have no
-- record here of which of them were ever attached to a product.
--
-- Kept as a column default rather than dropped after backfill so that any insert path which does
-- not name the column (a test fixture, a manual repair) also lands on PRIVATE rather than failing
-- or, worse, on PUBLIC.

-- The storefront's read path is `WHERE id = ? AND visibility = 'PUBLIC' AND status = 'READY'`.
-- Primary key alone already makes that a single-row lookup, so this index is not for that query —
-- it is for the eventual "how much is published" audit, and for the reaper to find published rows
-- whose product has gone away. Partial, because PUBLIC will always be the minority of this table.
CREATE INDEX idx_media_files_public ON media_files (id)
    WHERE visibility = 'PUBLIC' AND status = 'READY';
