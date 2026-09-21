package com.aura.order.cart;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.cart.dto.CartResponse;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Carts for shoppers with and without accounts.
 *
 * <p>The merge is the part worth the most attention: the plan singles it out as the step most
 * implementations forget, and its absence is very visible — the shopper signs in and their basket
 * appears to vanish.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final long USER = 42L;
    private static final long VARIANT = 7L;
    private static final long PRODUCT = 3L;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private CatalogGateway catalogGateway;

    private CartService service;
    private UUID guestToken;

    @BeforeEach
    void setUp() {
        service = new CartService(cartRepository, cartItemRepository, catalogGateway);
        guestToken = UUID.randomUUID();
    }

    private VariantSnapshot snapshot(long variantId, long price, int available, boolean purchasable) {
        return new VariantSnapshot(variantId, PRODUCT, 9L, "Shirt", "shirt", "Navy", "L",
            price, purchasable, available, 3L, java.util.List.of(1L, 3L));
    }

    private void catalogSays(VariantSnapshot... snapshots) {
        Map<Long, VariantSnapshot> byId = java.util.Arrays.stream(snapshots)
            .collect(java.util.stream.Collectors.toMap(VariantSnapshot::variantId, s -> s));
        lenient().when(catalogGateway.snapshotsFor(any())).thenReturn(byId);
    }

    private Cart cart(long id, Long userId, UUID token) {
        Cart cart = userId != null ? Cart.forUser(userId) : Cart.forGuest(token);
        cart.setId(id);
        return cart;
    }

    private CartItem item(long id, long cartId, long variantId, int quantity, long priceAtAdd) {
        CartItem item = CartItem.of(cartId, PRODUCT, variantId, quantity, priceAtAdd);
        item.setId(id);
        return item;
    }

    private void echoSaves() {
        lenient().when(cartRepository.save(any(Cart.class))).thenAnswer(i -> {
            Cart c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(1L);
            }
            return c;
        });
        lenient().when(cartItemRepository.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    class Guests {

        @Test
        @DisplayName("a guest can fill a cart with no account at all")
        void guestCanAddItems() {
            // Requirement 12 in one test: no user id anywhere in this flow.
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.empty());
            // No lookup for an existing line: there is no cart yet, so there cannot be one.
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(anyLong()))
                .thenReturn(List.of(), List.of(item(1L, 1L, VARIANT, 2, 250_000L)));
            echoSaves();

            CartResponse response = service.addItem(CartOwner.guest(guestToken), VARIANT, 2);

            assertThat(response.itemCount()).isEqualTo(2);
            assertThat(response.subtotal()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("viewing without a cart returns an empty one rather than creating a row")
        void viewingDoesNotCreateACart() {
            // Otherwise every visitor who never buys leaves an empty cart behind.
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.empty());

            assertThat(service.view(CartOwner.guest(guestToken)).items()).isEmpty();
            verify(cartRepository, never()).save(any());
        }

        @Test
        @DisplayName("a cart belongs to exactly one of a user or a token")
        void ownerIsExclusive() {
            assertThatThrownBy(() -> new CartOwner(USER, guestToken))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CartOwner(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AddingAndUpdating {

        @Test
        @DisplayName("adding the same variant twice raises the quantity, it does not add a line")
        void repeatAddIncrementsQuantity() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart existing = cart(1L, USER, null);
            CartItem line = item(1L, 1L, VARIANT, 2, 250_000L);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT)).thenReturn(Optional.of(line));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of(line));
            echoSaves();

            service.addItem(CartOwner.user(USER), VARIANT, 3);

            assertThat(line.getQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("adding more than is in stock is refused, counting what is already in the cart")
        void addRespectsExistingQuantity() {
            // 6 available, 4 already in the basket: asking for 3 more is 7, which is too many.
            catalogSays(snapshot(VARIANT, 250_000L, 6, true));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(1L, USER, null)));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT))
                .thenReturn(Optional.of(item(1L, 1L, VARIANT, 4, 250_000L)));

            assertThatThrownBy(() -> service.addItem(CartOwner.user(USER), VARIANT, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only 6 left");
        }

        @Test
        @DisplayName("a withdrawn product cannot be added")
        void withdrawnProductRefused() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, false));

            assertThatThrownBy(() -> service.addItem(CartOwner.user(USER), VARIANT, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no longer available");
        }

        @Test
        @DisplayName("a variant catalog has never heard of cannot be added")
        void unknownVariantRefused() {
            when(catalogGateway.snapshotsFor(any())).thenReturn(Map.of());

            assertThatThrownBy(() -> service.addItem(CartOwner.user(USER), VARIANT, 1))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("out of stock reads differently from no longer sold")
        void outOfStockHasItsOwnMessage() {
            // Two different problems: one might resolve itself, the other will not.
            catalogSays(snapshot(VARIANT, 250_000L, 0, true));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addItem(CartOwner.user(USER), VARIANT, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("out of stock");

            // Nothing was written: a rejected add must not leave an empty cart behind.
            verify(cartRepository, never()).save(any());
        }

        @Test
        @DisplayName("a quantity of zero or less is refused outright")
        void nonPositiveQuantityRefused() {
            assertThatThrownBy(() -> service.addItem(CartOwner.user(USER), VARIANT, 0))
                .isInstanceOf(BusinessRuleException.class);
            verifyNoInteractions(catalogGateway);
        }

        @Test
        @DisplayName("setting a quantity to zero removes the line")
        void zeroQuantityRemovesTheLine() {
            Cart existing = cart(1L, USER, null);
            CartItem line = item(1L, 1L, VARIANT, 2, 250_000L);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT)).thenReturn(Optional.of(line));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of());
            echoSaves();

            service.setQuantity(CartOwner.user(USER), VARIANT, 0);

            verify(cartItemRepository).delete(line);
        }

        @Test
        @DisplayName("updating a line that is not in the cart is a 404")
        void updatingAMissingLine() {
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(1L, USER, null)));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setQuantity(CartOwner.user(USER), VARIANT, 1))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Pricing {

        @Test
        @DisplayName("the subtotal uses today's price, not what things cost when added")
        void subtotalUsesCurrentPrice() {
            // Charging priceAtAdd would honour a price the shop has moved on from, which the
            // seller never agreed to.
            catalogSays(snapshot(VARIANT, 300_000L, 10, true));
            Cart existing = cart(1L, USER, null);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 2, 250_000L)));

            CartResponse response = service.view(CartOwner.user(USER));

            assertThat(response.subtotal()).isEqualTo(600_000L);
            assertThat(response.items().getFirst().priceChanged()).isTrue();
            assertThat(response.items().getFirst().priceAtAdd()).isEqualTo(250_000L);
        }

        @Test
        @DisplayName("an unchanged price is not flagged")
        void unchangedPriceIsNotFlagged() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(1L, USER, null)));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 1, 250_000L)));

            CartResponse response = service.view(CartOwner.user(USER));

            assertThat(response.items().getFirst().priceChanged()).isFalse();
            assertThat(response.hasIssues()).isFalse();
        }

        @Test
        @DisplayName("a line that outran its stock is flagged but keeps the rest of the cart usable")
        void unavailableLineIsFlagged() {
            catalogSays(snapshot(VARIANT, 250_000L, 1, true));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(1L, USER, null)));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 5, 250_000L)));

            CartResponse response = service.view(CartOwner.user(USER));

            assertThat(response.items().getFirst().available()).isFalse();
            assertThat(response.items().getFirst().unavailableReason()).contains("Only 1 left");
            assertThat(response.hasIssues()).isTrue();
            // Unbuyable lines do not contribute to what the shopper would pay.
            assertThat(response.subtotal()).isZero();
        }

        @Test
        @DisplayName("a variant deleted outright still renders, marked unbuyable")
        void deletedVariantStillRenders() {
            // Everything but the ids is gone. Failing the whole cart view would strand the shopper
            // with no way to remove the offending line.
            when(catalogGateway.snapshotsFor(any())).thenReturn(Map.of());
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(1L, USER, null)));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 1, 250_000L)));

            CartResponse response = service.view(CartOwner.user(USER));

            assertThat(response.items()).hasSize(1);
            assertThat(response.items().getFirst().available()).isFalse();
            assertThat(response.items().getFirst().unavailableReason()).contains("no longer sold");
        }
    }

    @Nested
    class MergingOnLogin {

        @Test
        @DisplayName("a guest basket survives signing in")
        void guestItemsMoveToTheAccount() {
            // The step most implementations forget. Without it the shopper signs in and their
            // basket appears to vanish.
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart guest = cart(1L, null, guestToken);
            Cart mine = cart(2L, USER, null);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(mine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 2, 250_000L)));
            when(cartItemRepository.findByCartIdAndVariantId(2L, VARIANT)).thenReturn(Optional.empty());
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(2L)).thenReturn(List.of());
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
            verify(cartItemRepository).save(captor.capture());
            assertThat(captor.getValue().getCartId()).isEqualTo(2L);
            assertThat(captor.getValue().getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("quantities are summed where both carts hold the same variant")
        void quantitiesAreSummed() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart guest = cart(1L, null, guestToken);
            Cart mine = cart(2L, USER, null);
            CartItem myLine = item(2L, 2L, VARIANT, 1, 250_000L);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(mine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 2, 250_000L)));
            when(cartItemRepository.findByCartIdAndVariantId(2L, VARIANT)).thenReturn(Optional.of(myLine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(2L)).thenReturn(List.of(myLine));
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            assertThat(myLine.getQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("the summed quantity is capped at what is actually in stock")
        void mergedQuantityCappedAtAvailable() {
            // Two carts can easily sum past the shelf. Carrying an impossible quantity into
            // checkout only fails later, and less clearly.
            catalogSays(snapshot(VARIANT, 250_000L, 4, true));
            Cart guest = cart(1L, null, guestToken);
            Cart mine = cart(2L, USER, null);
            CartItem myLine = item(2L, 2L, VARIANT, 3, 250_000L);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(mine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 3, 250_000L)));
            when(cartItemRepository.findByCartIdAndVariantId(2L, VARIANT)).thenReturn(Optional.of(myLine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(2L)).thenReturn(List.of(myLine));
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            assertThat(myLine.getQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("an item withdrawn while the guest was away is dropped, not fatal")
        void withdrawnItemsAreDropped() {
            // Failing the whole merge would lose everything else in the basket over one line.
            catalogSays(snapshot(VARIANT, 250_000L, 10, false));
            Cart guest = cart(1L, null, guestToken);
            Cart mine = cart(2L, USER, null);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(mine));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L))
                .thenReturn(List.of(item(1L, 1L, VARIANT, 2, 250_000L)));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(2L)).thenReturn(List.of());
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            verify(cartItemRepository, never()).save(any());
            verify(cartRepository).delete(guest);
        }

        @Test
        @DisplayName("the guest cart is deleted, so the cookie cannot resurrect it")
        void guestCartIsDeleted() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart guest = cart(1L, null, guestToken);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(cart(2L, USER, null)));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(anyLong())).thenReturn(List.of());
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            verify(cartRepository).delete(guest);
        }

        @Test
        @DisplayName("merging with no guest cart is a no-op, so a repeated call is harmless")
        void mergeIsIdempotent() {
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.empty());
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.empty());

            assertThat(service.mergeGuestCartInto(USER, guestToken).items()).isEmpty();
            verify(cartRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a shopper with no cart of their own gets one to merge into")
        void createsTargetCartWhenAbsent() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart guest = cart(1L, null, guestToken);
            when(cartRepository.findByCartToken(guestToken)).thenReturn(Optional.of(guest));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.empty());
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(anyLong())).thenReturn(List.of());
            echoSaves();

            service.mergeGuestCartInto(USER, guestToken);

            verify(cartRepository, atLeastOnce()).save(argThat(c -> USER == (c.getUserId() == null ? -1 : c.getUserId())));
        }
    }

    @Nested
    class RemovingAndClearing {

        @Test
        @DisplayName("removing a line leaves the rest of the cart alone")
        void removesOneLine() {
            catalogSays(snapshot(VARIANT, 250_000L, 10, true));
            Cart existing = cart(1L, USER, null);
            CartItem line = item(1L, 1L, VARIANT, 2, 250_000L);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT)).thenReturn(Optional.of(line));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of());
            echoSaves();

            assertThat(service.removeItem(CartOwner.user(USER), VARIANT).items()).isEmpty();
            verify(cartItemRepository).delete(line);
        }

        @Test
        @DisplayName("removing something that is not there is not an error")
        void removingAnAbsentLineIsHarmless() {
            // The shopper clicked remove twice, or on a stale page. Either way the outcome they
            // asked for already holds.
            Cart existing = cart(1L, USER, null);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdAndVariantId(1L, VARIANT)).thenReturn(Optional.empty());
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of());
            echoSaves();

            assertThat(service.removeItem(CartOwner.user(USER), VARIANT).items()).isEmpty();
            verify(cartItemRepository, never()).delete(any());
        }

        @Test
        @DisplayName("clearing empties the cart but keeps it")
        void clearEmptiesTheCart() {
            Cart existing = cart(1L, USER, null);
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of());
            echoSaves();

            assertThat(service.clear(CartOwner.user(USER)).items()).isEmpty();
            verify(cartItemRepository).deleteByCartId(1L);
            verify(cartRepository, never()).delete(any());
        }

        @Test
        @DisplayName("clearing a cart that never existed is a 404, not a silent success")
        void clearingWithoutACart() {
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clear(CartOwner.user(USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a change bumps updatedAt, so an active cart is not swept out from under the shopper")
        void writesTouchTheCart() {
            Cart existing = cart(1L, USER, null);
            existing.setUpdatedAt(java.time.OffsetDateTime.now().minusDays(20));
            when(cartRepository.findByUserId(USER)).thenReturn(Optional.of(existing));
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(1L)).thenReturn(List.of());
            echoSaves();

            service.clear(CartOwner.user(USER));

            assertThat(existing.getUpdatedAt()).isAfter(java.time.OffsetDateTime.now().minusMinutes(1));
        }
    }

    @Nested
    class Housekeeping {

        @Test
        @DisplayName("only guest carts are swept — an account's basket is theirs to keep")
        void sweepsOnlyGuestCarts() {
            when(cartRepository.deleteGuestCartsUpdatedBefore(any())).thenReturn(3);

            assertThat(service.sweepAbandonedGuestCarts(java.time.OffsetDateTime.now())).isEqualTo(3);
        }
    }
}
