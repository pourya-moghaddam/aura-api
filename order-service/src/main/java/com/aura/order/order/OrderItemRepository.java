package com.aura.order.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /** One seller's lines on one order. The filter is the security boundary, not a convenience. */
    List<OrderItem> findBySellerIdAndOrderId(Long sellerId, Long orderId);

    List<OrderItem> findBySellerIdAndOrderIdIn(Long sellerId, Collection<Long> orderIds);

    boolean existsBySellerIdAndOrderId(Long sellerId, Long orderId);
}
