package com.aura.order.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);
}
