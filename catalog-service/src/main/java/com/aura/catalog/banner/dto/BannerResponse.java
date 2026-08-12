package com.aura.catalog.banner.dto;

import com.aura.catalog.banner.Banner;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @param live whether it is on the homepage at this moment. Present so the control panel can show
 *             an admin why a banner they just saved is not visible — active but not yet started,
 *             or already finished — rather than leaving them to work it out from two timestamps.
 */
public record BannerResponse(
    Long id,
    UUID mediaId,
    String title,
    String linkUrl,
    int sortOrder,
    boolean isActive,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    boolean live
) {

    public static BannerResponse from(Banner banner) {
        return new BannerResponse(banner.getId(), banner.getMediaId(), banner.getTitle(),
            banner.getLinkUrl(), banner.getSortOrder(), banner.isActive(), banner.getStartsAt(),
            banner.getEndsAt(), banner.isLiveAt(OffsetDateTime.now()));
    }
}
