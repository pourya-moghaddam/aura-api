package com.aura.catalog.color;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ColorRepository extends JpaRepository<Color, Long> {

    List<Color> findAllByOrderBySortOrderAscNameAsc();

    List<Color> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    Optional<Color> findByNameIgnoreCase(String name);

    /**
     * Whether any variant still uses this colour.
     *
     * <p>The foreign key is {@code ON DELETE RESTRICT}, so the database would refuse the delete
     * anyway — this exists to turn that into a message naming the reason rather than a constraint
     * violation surfacing as a 500.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM product_variants WHERE color_id = :colorId)",
        nativeQuery = true)
    boolean isUsedByAnyVariant(@Param("colorId") Long colorId);
}
