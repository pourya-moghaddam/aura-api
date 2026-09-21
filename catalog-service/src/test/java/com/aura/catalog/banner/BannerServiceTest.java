package com.aura.catalog.banner;

import com.aura.catalog.banner.dto.BannerRequest;
import com.aura.catalog.banner.dto.BannerResponse;
import com.aura.catalog.media.MediaGateway;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BannerServiceTest {

    @Mock
    private BannerRepository bannerRepository;

    @Mock
    private MediaGateway mediaGateway;

    private BannerService service;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        service = new BannerService(bannerRepository, mediaGateway);
        mediaId = UUID.randomUUID();
    }

    private BannerRequest request(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return new BannerRequest(mediaId, "Sale", "/sale", 0, true, startsAt, endsAt);
    }

    private Banner banner(long id) {
        Banner banner = Banner.of(mediaId, "Sale", "/sale", 0);
        banner.setId(id);
        return banner;
    }

    private void echoSave() {
        when(bannerRepository.save(any(Banner.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a banner with a usable image is created")
    void createsWithUsableMedia() {
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        echoSave();

        BannerResponse response = service.create(request(null, null));

        assertThat(response.mediaId()).isEqualTo(mediaId);
        assertThat(response.live()).isTrue();
    }

    @Test
    @DisplayName("an unscanned or foreign image is refused")
    void unusableMediaRefused() {
        // The banner is the most prominent image on the site, so it is the worst possible place
        // for the scan gate to have been skipped.
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(false);

        assertThatThrownBy(() -> service.create(request(null, null)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not available");

        verify(bannerRepository, never()).save(any());
    }

    @Test
    @DisplayName("a window that ends before it starts is refused with a sentence, not a 500")
    void reversedWindowRefused() {
        OffsetDateTime now = OffsetDateTime.now();
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(now.plusDays(2), now.plusDays(1))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("after its start time");
    }

    @Test
    @DisplayName("a scheduled banner is not live before it starts")
    void notLiveBeforeStart() {
        Banner scheduled = banner(1L);
        scheduled.setStartsAt(OffsetDateTime.now().plusDays(1));

        assertThat(scheduled.isLiveAt(OffsetDateTime.now())).isFalse();
        assertThat(BannerResponse.from(scheduled).live()).isFalse();
    }

    @Test
    @DisplayName("an expired banner is not live")
    void notLiveAfterEnd() {
        Banner expired = banner(1L);
        expired.setEndsAt(OffsetDateTime.now().minusMinutes(1));

        assertThat(expired.isLiveAt(OffsetDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("an inactive banner is not live whatever its window says")
    void inactiveIsNeverLive() {
        Banner off = banner(1L);
        off.setActive(false);

        assertThat(off.isLiveAt(OffsetDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("a banner with no window at all is live")
    void unboundedWindowIsLive() {
        assertThat(banner(1L).isLiveAt(OffsetDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("a banner inside its window is live")
    void insideWindowIsLive() {
        Banner running = banner(1L);
        running.setStartsAt(OffsetDateTime.now().minusHours(1));
        running.setEndsAt(OffsetDateTime.now().plusHours(1));

        assertThat(running.isLiveAt(OffsetDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("changing only the schedule does not re-check media-service")
    void mediaNotRecheckedWhenUnchanged() {
        // Otherwise an admin cannot switch a banner off while media-service is down, which is
        // exactly when they might most want to.
        Banner existing = banner(1L);
        when(bannerRepository.findById(1L)).thenReturn(Optional.of(existing));
        echoSave();

        service.update(1L, new BannerRequest(mediaId, "Sale", "/sale", 0, false, null, null));

        verifyNoInteractions(mediaGateway);
    }

    @Test
    @DisplayName("swapping the image does re-check it")
    void newMediaIsChecked() {
        Banner existing = banner(1L);
        UUID replacement = UUID.randomUUID();
        when(bannerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(mediaGateway.isUsableBy(replacement)).thenReturn(true);
        echoSave();

        service.update(1L, new BannerRequest(replacement, "Sale", "/sale", 0, true, null, null));

        assertThat(existing.getMediaId()).isEqualTo(replacement);
    }

    @Test
    @DisplayName("the storefront list asks the database for the window, not for everything")
    void liveListDelegatesToTheQuery() {
        // Filtering in Java would mean a scheduled banner only starts when something happens to
        // reload; evaluating the window in the query means it starts on its own.
        when(bannerRepository.findLiveAt(any())).thenReturn(List.of(banner(1L)));

        assertThat(service.listLive()).hasSize(1);
        verify(bannerRepository).findLiveAt(any());
        verify(bannerRepository, never()).findAllByOrderBySortOrderAscIdAsc();
    }

    @Test
    @DisplayName("the admin list includes scheduled and expired banners")
    void adminListIncludesEverything() {
        when(bannerRepository.findAllByOrderBySortOrderAscIdAsc())
            .thenReturn(List.of(banner(1L), banner(2L)));

        assertThat(service.listAll()).hasSize(2);
    }

    @Test
    @DisplayName("a banner is deleted outright — nothing references it")
    void deleted() {
        Banner existing = banner(1L);
        when(bannerRepository.findById(1L)).thenReturn(Optional.of(existing));

        service.delete(1L);

        verify(bannerRepository).delete(existing);
    }

    @Test
    @DisplayName("an unknown banner is a 404")
    void unknownBanner() {
        when(bannerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("blank title and link are stored as null rather than empty strings")
    void blanksBecomeNull() {
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        echoSave();

        BannerResponse response = service.create(
            new BannerRequest(mediaId, "   ", null, 0, true, null, null));

        assertThat(response.title()).isNull();
        assertThat(response.linkUrl()).isNull();
    }
}
