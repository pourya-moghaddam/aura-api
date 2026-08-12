package com.aura.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * A seller's chosen value for one of the fields their category inherits.
 *
 * <p>The source of truth for product attributes, and the reason the denormalised
 * {@code products.attributes} JSONB is safe to trust: real foreign keys mean a value that is not on
 * the admin's list cannot be stored at all. The previous schema kept only the JSONB, so nothing
 * stopped a seller inventing one.
 */
@Entity
@Table(name = "product_field_values")
@Getter
@Setter
@NoArgsConstructor
public class ProductFieldValue {

    @EmbeddedId
    private Key key;

    public static ProductFieldValue of(long productId, long fieldId, long fieldValueId) {
        ProductFieldValue value = new ProductFieldValue();
        value.key = new Key(productId, fieldId, fieldValueId);
        return value;
    }

    public long getProductId() {
        return key.getProductId();
    }

    public long getFieldId() {
        return key.getFieldId();
    }

    public long getFieldValueId() {
        return key.getFieldValueId();
    }

    /**
     * The whole row is the key. {@code field_value_id} is part of it so a MULTI_SELECT field can
     * hold several values for one product.
     */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "product_id", nullable = false)
        private long productId;

        @Column(name = "field_id", nullable = false)
        private long fieldId;

        @Column(name = "field_value_id", nullable = false)
        private long fieldValueId;
    }
}
