package com.aura.order.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryMethodRepository extends JpaRepository<DeliveryMethod, Long> {

    /** What checkout offers. Retired methods stay in the table so past orders still resolve. */
    List<DeliveryMethod> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    /** Everything, including retired — the admin needs to see what exists to manage it. */
    List<DeliveryMethod> findAllByOrderBySortOrderAscNameAsc();

    Optional<DeliveryMethod> findByNameIgnoreCase(String name);
}
