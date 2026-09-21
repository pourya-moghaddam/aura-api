package com.aura.catalog.banner;

import com.aura.catalog.banner.dto.BannerRequest;
import com.aura.catalog.banner.dto.BannerResponse;
import com.aura.catalog.media.MediaGateway;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The homepage slider — requirement 6.
 */
@Service
@RequiredArgsConstructor
public class BannerService {

    private final BannerRepository bannerRepository;
    private final MediaGateway mediaGateway;

    /** What the storefront shows: active, within its window, in display order. */
    @Transactional(readOnly = true)
    public List<BannerResponse> listLive() {
        return bannerRepository.findLiveAt(OffsetDateTime.now()).stream()
            .map(BannerResponse::from).toList();
    }

    /** Everything, including scheduled and expired — the admin needs to see what exists. */
    @Transactional(readOnly = true)
    public List<BannerResponse> listAll() {
        return bannerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(BannerResponse::from).toList();
    }

    @Transactional
    public BannerResponse create(BannerRequest request) {
        requireUsableMedia(request.mediaId());
        requireSensibleWindow(request.startsAt(), request.endsAt());

        Banner banner = Banner.of(request.mediaId(), trimmed(request.title()),
            trimmed(request.linkUrl()), request.sortOrder());
        banner.setActive(request.isActive());
        banner.setStartsAt(request.startsAt());
        banner.setEndsAt(request.endsAt());

        return BannerResponse.from(bannerRepository.save(banner));
    }

    @Transactional
    public BannerResponse update(long id, BannerRequest request) {
        Banner banner = require(id);

        // Only re-checked when the image changes: an admin toggling a banner off should not be
        // blocked because media-service happens to be down.
        if (!banner.getMediaId().equals(request.mediaId())) {
            requireUsableMedia(request.mediaId());
            banner.setMediaId(request.mediaId());
        }
        requireSensibleWindow(request.startsAt(), request.endsAt());

        banner.setTitle(trimmed(request.title()));
        banner.setLinkUrl(trimmed(request.linkUrl()));
        banner.setSortOrder(request.sortOrder());
        banner.setActive(request.isActive());
        banner.setStartsAt(request.startsAt());
        banner.setEndsAt(request.endsAt());
        banner.touch();

        return BannerResponse.from(bannerRepository.save(banner));
    }

    /**
     * Deleted outright, unlike a product. A banner is presentation — nothing references it, no
     * order history depends on it, and an admin removing one from the slider means to be rid of it.
     */
    @Transactional
    public void delete(long id) {
        bannerRepository.delete(require(id));
    }

    @Transactional(readOnly = true)
    public Banner require(long id) {
        return bannerRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Banner", id));
    }

    /**
     * The same gate products use: the file must have passed scanning and belong to the caller.
     * A banner is the most prominent image on the site, so an unscanned one is the worst place to
     * find out the check was skipped.
     */
    private void requireUsableMedia(java.util.UUID mediaId) {
        if (!mediaGateway.isUsableBy(mediaId)) {
            throw new BusinessRuleException("media-not-usable",
                "That file is not available. It may still be processing, may have failed the "
                    + "security scan, or may not belong to you.");
        }
        // A banner is by definition public - it is the first thing an anonymous visitor sees on
        // the home page - so the file has to be readable without a token, exactly as product
        // images are.
        mediaGateway.publish(mediaId);
    }

    /**
     * The database's {@code ck_banners_window} constraint says the same thing. This exists so a
     * reversed window is a sentence rather than a constraint violation surfacing as a 500.
     */
    private void requireSensibleWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BusinessRuleException("banner-window-invalid",
                "A banner's end time must be after its start time.");
        }
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
