package com.aura.auth.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A saved delivery address in a user's address book.
 *
 * <p>Referenced by {@code user_id} rather than a JPA association to {@code User}: the two are only
 * ever loaded together in one direction, and a bidirectional mapping would invite lazy-loading the
 * whole address list every time a user is fetched for an unrelated reason — a login, say.
 *
 * <p>Orders <em>copy</em> these fields rather than referencing the row. Editing an address here must
 * never rewrite what a past order says was delivered where.
 */
@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** User-facing label: "Home", "Office". Optional. */
    @Column(length = 100)
    private String title;

    @Column(name = "recipient_first_name", nullable = false, length = 100)
    private String recipientFirstName;

    @Column(name = "recipient_last_name", nullable = false, length = 100)
    private String recipientLastName;

    /** E.164. The recipient is not always the account holder, so this is not the user's phone. */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String province;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String line1;

    @Column(columnDefinition = "TEXT")
    private String line2;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
