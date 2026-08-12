package com.aura.catalog.banner.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @param linkUrl  where the slide leads. Restricted to a relative path or an absolute http(s) URL:
 *                 an unchecked value here becomes an anchor href on the homepage, and
 *                 {@code javascript:} in that position is stored XSS.
 * @param startsAt null means the banner is live as soon as it is active
 * @param endsAt   null means it runs until switched off
 */
public record BannerRequest(
    @NotNull(message = "Media id is required")
    UUID mediaId,

    @Size(max = 150)
    String title,

    @Size(max = 500)
    @Pattern(regexp = "^(/[^\\s]*|https?://[^\\s]+)$",
        message = "Link must be a relative path or an http(s) URL")
    String linkUrl,

    Integer sortOrder,

    Boolean isActive,

    OffsetDateTime startsAt,

    OffsetDateTime endsAt
) {

    public BannerRequest {
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (isActive == null) {
            isActive = true;
        }
    }
}
