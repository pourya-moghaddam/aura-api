package com.aura.order.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    Optional<Cart> findByCartToken(UUID cartToken);

    /**
     * Sweeps abandoned guest carts.
     *
     * <p>Only guest carts: a signed-in shopper's cart is theirs to keep, and deleting it because
     * they were away for a month is losing their basket, not tidying up. Items go with it through
     * the cascade.
     */
    @Modifying
    @Query("DELETE FROM Cart c WHERE c.userId IS NULL AND c.updatedAt < :before")
    int deleteGuestCartsUpdatedBefore(@Param("before") OffsetDateTime before);
}
