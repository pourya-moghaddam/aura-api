package com.aura.order.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartIdOrderByAddedAtAscIdAsc(Long cartId);

    Optional<CartItem> findByCartIdAndVariantId(Long cartId, Long variantId);

    @Modifying
    @Query("DELETE FROM CartItem i WHERE i.cartId = :cartId")
    void deleteByCartId(@Param("cartId") Long cartId);
}
