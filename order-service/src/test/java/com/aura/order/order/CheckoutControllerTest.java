package com.aura.order.order;

import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartOwnerResolver;
import com.aura.order.cart.CartTokenCookie;
import com.aura.order.config.CartProperties;
import com.aura.order.order.dto.OrderResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of checkout, which is where a bug lived that no service-level test could see.
 *
 * <p>{@link CheckoutService} is handed a {@link CartOwner}; the controller has to <em>resolve</em>
 * one from a cookie. Clearing that cookie on a successful checkout looked tidy and broke the retry
 * it mattered most to survive — the second request arrived with no cookie, was refused here as
 * "your basket is empty", and never reached the idempotency key that would have returned the
 * original order. Found against the running stack, pinned here.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    private static final String BODY = """
        {"buyerFirstName":"Ali","buyerLastName":"Rezai","buyerPhone":"09121234567",
         "province":"Tehran","city":"Tehran","addressLine":"Somewhere 12",
         "postalCode":"1234567890","deliveryMethodId":3,"idempotencyKey":"key-1"}
        """;

    @Mock
    private CheckoutService checkoutService;

    private MockMvc mockMvc;
    private UUID token;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID();
        CartOwnerResolver resolver = new CartOwnerResolver(new CartProperties(Duration.ofDays(30), false));
        mockMvc = MockMvcBuilders
            .standaloneSetup(new CheckoutController(checkoutService, resolver))
            .build();
    }

    private OrderResponse placed() {
        return new OrderResponse(1L, "ABCDEFGHJK", PaymentStatus.PENDING, FulfillmentStatus.PENDING,
            OrderSource.CUSTOMER, "Ali Rezai", "+989121234567", Map.of(), "1234567890",
            "Post", 0L, null, 0L, 1L, 1L, List.of(), null, null);
    }

    @Test
    @DisplayName("the cart cookie survives checkout, so the retry can be replayed")
    void doesNotClearTheCartCookie() {
        when(checkoutService.checkout(any(), any())).thenReturn(placed());

        var response = post("/api/orders/checkout")
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY)
            .cookie(new Cookie(CartTokenCookie.NAME, token.toString()));

        try {
            String setCookie = mockMvc.perform(response)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Set-Cookie");

            // Nothing at all, or at least not an expiry. A Max-Age=0 here is the bug returning.
            assertThat(setCookie == null || !setCookie.contains("Max-Age=0")).isTrue();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("the guest is identified by their cookie, not asked to sign in")
    void resolvesTheGuestFromTheCookie() throws Exception {
        when(checkoutService.checkout(any(), any())).thenReturn(placed());

        mockMvc.perform(post("/api/orders/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .cookie(new Cookie(CartTokenCookie.NAME, token.toString())))
            .andExpect(status().isCreated());

        ArgumentCaptor<CartOwner> owner = ArgumentCaptor.forClass(CartOwner.class);
        verify(checkoutService).checkout(owner.capture(), any());
        assertThat(owner.getValue().cartToken()).isEqualTo(token);
        assertThat(owner.getValue().isUser()).isFalse();
    }

    @Test
    @DisplayName("a checkout with no cookie at all is refused rather than issued one")
    void doesNotMintATokenForAStrayCheckout() throws Exception {
        // A checkout with no basket is an error, not the start of one. Minting a cookie here
        // would leave a token behind for every stray POST.
        // Standalone MockMvc has no exception handler wired in, so the refusal surfaces as the
        // exception itself rather than as the 422 the real stack renders from it.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mockMvc.perform(post("/api/orders/checkout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)))
            .rootCause()
            .isInstanceOf(com.aura.common.web.error.BusinessRuleException.class)
            .hasMessageContaining("basket is empty");

        verify(checkoutService, org.mockito.Mockito.never()).checkout(any(), any());
    }

    @Test
    @DisplayName("the response points at where the order can be tracked")
    void locationIsTheTrackingUrl() throws Exception {
        when(checkoutService.checkout(any(), any())).thenReturn(placed());

        mockMvc.perform(post("/api/orders/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .cookie(new Cookie(CartTokenCookie.NAME, token.toString())))
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Location", "/api/orders/track/ABCDEFGHJK"));
    }
}
