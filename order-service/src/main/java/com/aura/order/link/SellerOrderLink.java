package com.aura.order.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A link a seller sends to a buyer so they can pay for an order composed for them —
 * requirement 1.
 *
 * <p>The token is stored <em>hashed</em>. It is a bearer credential handed to someone with no
 * account, presented by anyone holding it, so a leaked database must not hand out working links.
 * The same reasoning as the refresh tokens in auth-service, for the same reason.
 *
 * <p>{@code usedAt} records when it was paid, not when it was opened. A buyer who reads the page
 * and comes back an hour later to pay is ordinary; a link that stops working the moment it is
 * looked at is not a link.
 */
@Entity
@Table(name = "seller_order_links")
@Getter
@Setter
@NoArgsConstructor
public class SellerOrderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static SellerOrderLink of(Long orderId, Long sellerId, String tokenHash,
                                     OffsetDateTime expiresAt) {
        SellerOrderLink link = new SellerOrderLink();
        link.orderId = orderId;
        link.sellerId = sellerId;
        link.tokenHash = tokenHash;
        link.expiresAt = expiresAt;
        link.createdAt = OffsetDateTime.now();
        return link;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed() {
        this.usedAt = OffsetDateTime.now();
    }
}
