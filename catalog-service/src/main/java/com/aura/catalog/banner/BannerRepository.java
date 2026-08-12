package com.aura.catalog.banner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findAllByOrderBySortOrderAscIdAsc();

    /**
     * The slides the storefront should show right now.
     *
     * <p>The window is evaluated in the query rather than by filtering in Java, so a scheduled
     * banner starts and stops on its own with nothing to trigger it. A null bound means unbounded
     * on that side — most banners have neither.
     */
    @Query("""
        SELECT b FROM Banner b
        WHERE b.isActive = true
          AND (b.startsAt IS NULL OR b.startsAt <= :now)
          AND (b.endsAt IS NULL OR b.endsAt > :now)
        ORDER BY b.sortOrder ASC, b.id ASC
        """)
    List<Banner> findLiveAt(@Param("now") OffsetDateTime now);
}
