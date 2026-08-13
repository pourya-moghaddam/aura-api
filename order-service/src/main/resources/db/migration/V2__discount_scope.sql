/*
 * Scoped discount codes: "20% off all shoes" rather than only "20% off everything".
 *
 * Referenced ids belong to catalog-service, so there is nothing here to point a foreign key at.
 * That is the reason for an array column rather than a join table: a join table would imply a
 * referential guarantee this database cannot make, and would read as though it could.
 */
ALTER TABLE discount_codes
    ADD COLUMN scope     VARCHAR(20) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN scope_ids BIGINT[]    NOT NULL DEFAULT '{}';

ALTER TABLE discount_codes
    ADD CONSTRAINT ck_discount_scope CHECK (scope IN ('ORDER', 'CATEGORY', 'PRODUCT')),
    /*
     * A scoped code with nothing to match would apply to no basket ever assembled - it is not a
     * restriction, it is a code that silently never works. An order-wide code with ids is the
     * same mistake from the other side: the ids look meaningful and are ignored.
     */
    ADD CONSTRAINT ck_discount_scope_ids CHECK (
        (scope = 'ORDER' AND cardinality(scope_ids) = 0)
        OR (scope <> 'ORDER' AND cardinality(scope_ids) > 0));

-- Answers "which codes touch this category" when an admin archives one.
CREATE INDEX idx_discount_scope_ids ON discount_codes USING GIN (scope_ids)
    WHERE scope <> 'ORDER';
