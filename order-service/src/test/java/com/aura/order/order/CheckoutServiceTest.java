package com.aura.order.order;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.order.cart.Cart;
import com.aura.order.cart.CartItem;
import com.aura.order.cart.CartItemRepository;
import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartRepository;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import com.aura.order.delivery.DeliveryMethod;
import com.aura.order.delivery.DeliveryMethodService;
import com.aura.order.discount.DiscountService;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Turning a basket into an order.
 *
 * <p>The assertions worth reading are the ones about <em>order of operations</em>. Checkout does
 * several things that cannot all be undone — it holds stock in another service and spends a
 * discount code — so what matters is that nothing irreversible happens until everything reversible
 * has succeeded, and that the money is arithmetic on figures read now rather than on figures the
 * cart remembers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceTest {

    private static final UUID TOKEN = UUID.randomUUID();
    private static final long VARIANT = 7L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private CatalogGateway catalogGateway;

    @Mock
    private DeliveryMethodService deliveryMethodService;

    @Mock
    private DiscountService discountService;

    @InjectMocks
    private CheckoutService checkoutService;

    private Cart cart;
    private DeliveryMethod delivery;

    @BeforeEach
    void setUp() {
        cart = Cart.forGuest(TOKEN);
        cart.setId(11L);

        delivery = DeliveryMethod.of("Post", null, 40_000L, 0);
        delivery.setId(3L);

        when(cartRepository.findByCartToken(TOKEN)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(11L))
            .thenReturn(List.of(cartItem(2)));
        when(catalogGateway.snapshotsFor(anyCollection()))
            .thenReturn(Map.of(VARIANT, snapshot(500_000L, 5, true)));
        when(deliveryMethodService.requireSelectable(3L)).thenReturn(delivery);
        when(orderRepository.existsByTraceCode(anyString())).thenReturn(false);
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(99L);
            return order;
        });
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CartItem cartItem(int quantity) {
        return CartItem.of(11L, 4L, VARIANT, quantity, 500_000L);
    }

    private VariantSnapshot snapshot(long price, int available, boolean purchasable) {
        return new VariantSnapshot(VARIANT, 4L, 9L, "Shirt", "shirt", "Navy", "L",
            price, purchasable, available);
    }

    private CheckoutRequest request(String discountCode, String idempotencyKey) {
        return new CheckoutRequest("Ali", "Rezai", "09121234567", "Tehran", "Tehran",
            "Somewhere 12", "1234567890", 3L, discountCode, idempotencyKey);
    }

    private OrderResponse checkout() {
        return checkoutService.checkout(CartOwner.guest(TOKEN), request(null, null));
    }

    @Nested
    @DisplayName("the money")
    class Money {

        @Test
        @DisplayName("the total is the subtotal plus delivery")
        void totalIncludesDelivery() {
            OrderResponse order = checkout();

            assertThat(order.subtotal()).isEqualTo(1_000_000L);
            assertThat(order.deliveryFee()).isEqualTo(40_000L);
            assertThat(order.total()).isEqualTo(1_040_000L);
        }

        @Test
        @DisplayName("the price is read from catalog now, not taken from the cart")
        void repricesAtCheckout() {
            // The shopper has been filling in an address; the shop may have moved on. Charging the
            // remembered price honours a figure the seller never agreed to.
            when(catalogGateway.snapshotsFor(anyCollection()))
                .thenReturn(Map.of(VARIANT, snapshot(600_000L, 5, true)));

            assertThat(checkout().subtotal()).isEqualTo(1_200_000L);
        }

        @Test
        @DisplayName("a discount comes off the subtotal, before delivery is added")
        void discountAppliesToGoodsNotPostage() {
            // Otherwise "50% off" quietly discounts the courier too, and the shop pays the
            // difference on every order.
            when(discountService.redeem(anyString(), anyLong(), any(), anyLong()))
                .thenReturn(new DiscountService.Redemption(5L, "SUMMER", 100_000L));

            OrderResponse order = checkoutService.checkout(
                CartOwner.guest(TOKEN), request("summer", null));

            assertThat(order.discountAmount()).isEqualTo(100_000L);
            assertThat(order.total()).isEqualTo(1_000_000L - 100_000L + 40_000L);
        }

        @Test
        @DisplayName("the line total is stored, not left to be recomputed")
        void lineTotalsAreStored() {
            checkout();

            ArgumentCaptor<List<OrderItem>> saved = ArgumentCaptor.captor();
            verify(orderItemRepository).saveAll(saved.capture());
            OrderItem item = saved.getValue().getFirst();

            assertThat(item.getUnitPrice()).isEqualTo(500_000L);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getLineTotal()).isEqualTo(1_000_000L);
        }
    }

    @Nested
    @DisplayName("order of operations")
    class Ordering {

        @Test
        @DisplayName("stock is held only after the order exists and the discount is spent")
        void stockIsHeldLast() {
            // The reservation is the one step that cannot be rolled back with the transaction, so
            // it goes last: anything that fails before it leaves no trace at all.
            when(discountService.redeem(anyString(), anyLong(), any(), anyLong()))
                .thenReturn(new DiscountService.Redemption(5L, "SUMMER", 1L));

            checkoutService.checkout(CartOwner.guest(TOKEN), request("summer", null));

            var inOrder = org.mockito.Mockito.inOrder(
                orderRepository, discountService, catalogGateway);
            inOrder.verify(orderRepository).saveAndFlush(any());
            inOrder.verify(discountService).redeem(anyString(), anyLong(), any(), anyLong());
            inOrder.verify(catalogGateway).reserveStock(anyLong(), any());
        }

        @Test
        @DisplayName("an unbuyable line stops the checkout before anything is written")
        void nothingIsWrittenWhenTheBasketHasChanged() {
            when(catalogGateway.snapshotsFor(anyCollection()))
                .thenReturn(Map.of(VARIANT, snapshot(500_000L, 5, false)));

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no longer sold");

            verify(orderRepository, never()).saveAndFlush(any());
            verify(catalogGateway, never()).reserveStock(anyLong(), any());
            verify(discountService, never()).redeem(anyString(), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("too little stock stops it too, naming what is left")
        void insufficientStock() {
            when(catalogGateway.snapshotsFor(anyCollection()))
                .thenReturn(Map.of(VARIANT, snapshot(500_000L, 1, true)));

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only 1 left");

            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("every problem is reported at once, not one per attempt")
        void allProblemsAtOnce() {
            // A shopper made to discover their problems one submission at a time abandons the
            // basket before the third.
            CartItem second = CartItem.of(11L, 5L, 8L, 1, 100_000L);
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(11L))
                .thenReturn(List.of(cartItem(2), second));
            when(catalogGateway.snapshotsFor(anyCollection())).thenReturn(Map.of(
                VARIANT, snapshot(500_000L, 0, true),
                8L, new VariantSnapshot(8L, 5L, 9L, "Hat", "hat", null, null,
                    100_000L, false, 4)));

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .hasMessageContaining("Shirt")
                .hasMessageContaining("Hat");
        }

        @Test
        @DisplayName("an empty basket is refused")
        void emptyBasket() {
            when(cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(11L)).thenReturn(List.of());

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("basket is empty");
        }

        @Test
        @DisplayName("no basket at all is refused the same way")
        void noBasket() {
            when(cartRepository.findByCartToken(TOKEN)).thenReturn(Optional.empty());

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("basket is empty");
        }

        @Test
        @DisplayName("a withdrawn delivery method is refused even though it is never offered")
        void withdrawnDeliveryMethod() {
            // The id arrives in a request body. A checkout replayed from a stale page would
            // otherwise ship at a price the shop has withdrawn.
            when(deliveryMethodService.requireSelectable(3L))
                .thenThrow(new BusinessRuleException("delivery-method-unavailable", "gone"));

            assertThatThrownBy(CheckoutServiceTest.this::checkout)
                .isInstanceOf(BusinessRuleException.class);
            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("the basket is emptied once the order is placed")
        void cartIsCleared() {
            checkout();

            verify(cartItemRepository).deleteByCartId(11L);
            verify(cartRepository).delete(cart);
        }
    }

    @Nested
    @DisplayName("the order itself")
    class TheOrder {

        @Test
        @DisplayName("a guest order carries no user, and a signed-in one does")
        void ownership() {
            checkout();
            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getUserId()).isNull();

            Cart mine = Cart.forUser(42L);
            mine.setId(11L);
            when(cartRepository.findByUserId(42L)).thenReturn(Optional.of(mine));
            checkoutService.checkout(CartOwner.user(42L), request(null, null));

            verify(orderRepository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("the trace code is not the id")
        void traceCodeIsOpaque() {
            OrderResponse order = checkout();

            assertThat(order.traceCode()).hasSize(10);
            assertThat(order.traceCode()).isNotEqualTo(String.valueOf(order.id()));
        }

        @Test
        @DisplayName("a colliding trace code is retried rather than thrown")
        void retriesOnCollision() {
            when(orderRepository.existsByTraceCode(anyString())).thenReturn(true, false);

            assertThat(checkout().traceCode()).hasSize(10);
            verify(orderRepository, org.mockito.Mockito.times(2)).existsByTraceCode(anyString());
        }

        @Test
        @DisplayName("the delivery method's name and fee are snapshotted, not referenced")
        void deliverySnapshot() {
            // An admin repricing delivery tomorrow must not change what this customer paid.
            OrderResponse order = checkout();

            assertThat(order.deliveryName()).isEqualTo("Post");
            assertThat(order.deliveryFee()).isEqualTo(40_000L);
        }

        @Test
        @DisplayName("the seller is recorded on the line, from catalog rather than from the client")
        void sellerComesFromCatalog() {
            checkout();

            ArgumentCaptor<List<OrderItem>> saved = ArgumentCaptor.captor();
            verify(orderItemRepository).saveAll(saved.capture());
            assertThat(saved.getValue().getFirst().getSellerId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("the address is snapshotted whole")
        void addressSnapshot() {
            OrderResponse order = checkout();

            assertThat(order.address())
                .containsEntry("province", "Tehran")
                .containsEntry("addressLine", "Somewhere 12");
            assertThat(order.postalCode()).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("the phone number is stored canonically, however it was typed")
        void phoneIsNormalised() {
            // One shape in the database - the same +98 canonical form auth-service stores - so an
            // order placed as 0912... and an account registered as +98912... are the same person.
            assertThat(checkout().buyerPhone()).isEqualTo("+989121234567");

            OrderResponse order = checkoutService.checkout(CartOwner.guest(TOKEN),
                new CheckoutRequest("Ali", "Rezai", "0912 123 4567", "Tehran", "Tehran",
                    "Somewhere 12", "1234567890", 3L, null, null));

            assertThat(order.buyerPhone()).isEqualTo("+989121234567");
        }

        @Test
        @DisplayName("a phone number that is not Iranian is refused")
        void badPhone() {
            assertThatThrownBy(() -> checkoutService.checkout(CartOwner.guest(TOKEN),
                new CheckoutRequest("Ali", "Rezai", "12345", "Tehran", "Tehran",
                    "Somewhere 12", "1234567890", 3L, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("a new order is pending payment and pending fulfillment")
        void initialStatuses() {
            OrderResponse order = checkout();

            assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order.status()).isEqualTo(FulfillmentStatus.PENDING);
            assertThat(order.source()).isEqualTo(OrderSource.CUSTOMER);
        }
    }

    @Nested
    @DisplayName("an order a seller composed")
    class SellerComposed {

        private List<CheckoutService.RequestedLine> lines() {
            return List.of(new CheckoutService.RequestedLine(VARIANT, 2));
        }

        @Test
        @DisplayName("goes through the same path as an ordinary checkout")
        void sameOrderingAsCheckout() {
            // Not a second implementation: the sequence - validate, write, reserve last - is what
            // stops an order existing with no stock held for it.
            checkoutService.placeForSeller(9L, request(null, null), lines(), null);

            var inOrder = org.mockito.Mockito.inOrder(orderRepository, catalogGateway);
            inOrder.verify(orderRepository).saveAndFlush(any());
            inOrder.verify(catalogGateway).reserveStock(anyLong(), any());
        }

        @Test
        @DisplayName("is marked as coming from a seller link, with no user attached")
        void recordsItsSource() {
            checkoutService.placeForSeller(9L, request(null, null), lines(), null);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getSource()).isEqualTo(OrderSource.SELLER_LINK);
            assertThat(saved.getValue().getUserId()).isNull();
        }

        @Test
        @DisplayName("cannot contain another seller's product")
        void refusesForeignProducts() {
            // The seller id comes from catalog's snapshot, not from the request, so a seller
            // cannot claim a line by asserting ownership of it. Otherwise anyone with a seller
            // account could compose an order from the whole catalogue and take a link to it.
            assertThatThrownBy(() ->
                checkoutService.placeForSeller(999L, request(null, null), lines(), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only contain your own products");

            verify(orderRepository, never()).saveAndFlush(any());
            verify(catalogGateway, never()).reserveStock(anyLong(), any());
        }

        @Test
        @DisplayName("an empty order is refused")
        void refusesEmpty() {
            assertThatThrownBy(() ->
                checkoutService.placeForSeller(9L, request(null, null), List.of(), null))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("does not touch anybody's cart")
        void leavesCartsAlone() {
            // The buyer is on the telephone. There is no basket to empty, and emptying the
            // seller's own would be a peculiar thing to do.
            checkoutService.placeForSeller(9L, request(null, null), lines(), null);

            verify(cartRepository, never()).delete(any());
            verify(cartItemRepository, never()).deleteByCartId(anyLong());
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a replayed checkout returns the original order instead of buying twice")
        void replayReturnsTheOriginal() {
            Order original = new Order();
            original.setId(99L);
            original.setTraceCode("ABCDEFGHJK");
            original.setSubtotal(1L);
            original.setTotal(1L);
            original.setDeliveryFee(0L);
            original.setDiscountAmount(0L);
            original.setBuyerFirstName("Ali");
            original.setBuyerLastName("Rezai");
            when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));
            when(orderItemRepository.findByOrderIdOrderByIdAsc(99L)).thenReturn(List.of());

            OrderResponse order = checkoutService.checkout(
                CartOwner.guest(TOKEN), request(null, "key-1"));

            assertThat(order.traceCode()).isEqualTo("ABCDEFGHJK");
            verify(orderRepository, never()).saveAndFlush(any());
            verify(catalogGateway, never()).reserveStock(anyLong(), any());
        }

        @Test
        @DisplayName("the lock is taken before the lookup, not after")
        void locksBeforeLooking() {
            // Two tabs submitting at once both miss the lookup otherwise, and the second is
            // refused by the unique index - a 500 on exactly the retry the key exists to prevent.
            when(orderRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());

            checkoutService.checkout(CartOwner.guest(TOKEN), request(null, "key-2"));

            var inOrder = org.mockito.Mockito.inOrder(orderRepository);
            inOrder.verify(orderRepository).lockIdempotencyKey("key-2");
            inOrder.verify(orderRepository).findByIdempotencyKey("key-2");
        }

        @Test
        @DisplayName("no key means no lock and no replay protection")
        void withoutAKey() {
            checkout();

            verify(orderRepository, never()).lockIdempotencyKey(anyString());
        }

        @Test
        @DisplayName("a blank key is treated as absent rather than stored")
        void blankKey() {
            // Otherwise the first empty-string checkout takes the key and every later one replays
            // that stranger's order back at them.
            checkoutService.checkout(CartOwner.guest(TOKEN), request(null, "   "));

            verify(orderRepository, never()).lockIdempotencyKey(anyString());
            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getIdempotencyKey()).isNull();
        }
    }
}
