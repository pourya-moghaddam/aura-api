package com.aura.catalog.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Stock for one variant.
 *
 * <p>Its own table rather than columns on {@code product_variants}, because these are the hottest
 * writes in the system during a sale and they would otherwise contend with every catalogue read of
 * a name, price or description.
 *
 * <p>Two counters, not one. {@code quantityOnHand} is what physically exists; {@code
 * quantityReserved} is what unpaid orders are holding. Selling against a single number means either
 * decrementing before payment — and losing the stock when the shopper abandons the checkout — or
 * decrementing after, and selling the same last unit to two people.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static Inventory forVariant(long variantId, int onHand) {
        Inventory inventory = new Inventory();
        inventory.variantId = variantId;
        inventory.quantityOnHand = onHand;
        inventory.quantityReserved = 0;
        inventory.updatedAt = OffsetDateTime.now();
        return inventory;
    }

    /** What a shopper may still buy. Everything else is spoken for. */
    public int available() {
        return quantityOnHand - quantityReserved;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
