package com.aura.catalog.field;

/**
 * Mirrors the {@code ck_fields_data_type} check constraint.
 *
 * <p>SELECT and MULTI_SELECT are all requirement 5 asks for, and are the only two the product form
 * and the search facets understand today. NUMBER and BOOLEAN exist so they can be added without a
 * migration that changes what the column means — but nothing consumes them yet, so
 * {@link #usesEnumeratedValues()} is what code should branch on rather than testing for a
 * particular constant.
 */
public enum FieldDataType {

    SELECT,
    MULTI_SELECT,
    NUMBER,
    BOOLEAN;

    /** Whether an admin defines the allowed values for this field up front. */
    public boolean usesEnumeratedValues() {
        return this == SELECT || this == MULTI_SELECT;
    }

    /** Whether a product may carry more than one value for this field. */
    public boolean allowsMultipleValues() {
        return this == MULTI_SELECT;
    }
}
