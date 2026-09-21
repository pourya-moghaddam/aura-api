package com.aura.catalog.banner;

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
import java.util.UUID;

/**
 * A homepage slider image — requirement 6.
 *
 * <p>Scheduling is part of the row rather than something an admin does by hand at the right moment:
 * a sale banner that has to be switched on at midnight and off three days later is the normal case,
 * and doing it manually means someone is awake at midnight or the banner is wrong for a while.
 */
@Entity
@Table(name = "banners")
@Getter
@Setter
@NoArgsConstructor
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** An id from media-service. Checked to be servable before the banner can go live. */
    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(length = 150)
    private String title;

    /** Where the slide leads. Null for a purely decorative banner. */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /** Null means "already started". */
    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    /** Null means "runs until switched off". */
    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static Banner of(UUID mediaId, String title, String linkUrl, int sortOrder) {
        Banner banner = new Banner();
        banner.mediaId = mediaId;
        banner.title = title;
        banner.linkUrl = linkUrl;
        banner.sortOrder = sortOrder;
        banner.createdAt = OffsetDateTime.now();
        banner.updatedAt = banner.createdAt;
        return banner;
    }

    /**
     * Whether this banner should be on the homepage right now.
     *
     * <p>Duplicated by the repository's query, which is what the storefront actually uses — this is
     * for the control panel, so an admin can see why a banner they just saved is not showing.
     */
    public boolean isLiveAt(OffsetDateTime moment) {
        return isActive
            && (startsAt == null || !startsAt.isAfter(moment))
            && (endsAt == null || endsAt.isAfter(moment));
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
