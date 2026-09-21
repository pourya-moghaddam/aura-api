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
import com.aura.order.delivery.DeliveryMethodRepository;
import com.aura.order.delivery.DeliveryMethodService;
import com.aura.order.discount.DiscountCode;
import com.aura.order.discount.DiscountCodeRepository;
import com.aura.order.discount.DiscountRedemptionRepository;
import com.aura.order.discount.DiscountService;
import com.aura.order.discount.DiscountType;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checkout against a real database.
 *
 * <p>Two things here cannot be tested with mocks. The first is atomicity: checkout writes an
 * order, its items and a discount redemption, and holds stock in another service — if the last
 * step fails, every earlier one has to disappear, and only a real transaction can be asked whether
 * it did. The second is the idempotency lock, whose entire purpose is to order two transactions
 * that would otherwise both pass the same check.
 *
 * <p>Catalog is stubbed rather than run: what is under test is order-service's own bookkeeping,
 * and the stub is what lets the reservation be made to fail on demand.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CheckoutService.class, DiscountService.class, DeliveryMethodService.class,
    CheckoutIT.StubCatalog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long VARIANT = 7L;

    /**
     * Stands in for catalog. Records what it was asked to hold, and can be told to refuse — the
     * only way to exercise "the reservation failed after everything else succeeded".
     */
    @TestConfiguration
    static class StubCatalog {

        @Bean
        CatalogGateway catalogGateway() {
            return new RecordingCatalogGateway();
        }
    }

    static class RecordingCatalogGateway implements CatalogGateway {

        volatile boolean refuseReservations;
        final List<Long> reservedOrders = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public Map<Long, VariantSnapshot> snapshotsFor(Collection<Long> variantIds) {
            return Map.of(VARIANT, new VariantSnapshot(VARIANT, 4L, 9L, "Shirt", "shirt",
                "Navy", "L", 500_000L, true, 50, 3L, List.of(1L, 3L)));
        }

        @Override
        public void reserveStock(long orderId, List<StockLine> lines) {
            if (refuseReservations) {
                throw new BusinessRuleException("insufficient-stock", "Gone while you were typing.");
            }
            reservedOrders.add(orderId);
        }

        @Override
        public void releaseStock(long orderId) {
        }

        @Override
        public void commitStock(long orderId) {
        }
    }

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private DeliveryMethodRepository deliveryMethodRepository;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Autowired
    private DiscountRedemptionRepository discountRedemptionRepository;

    @Autowired
    private CatalogGateway catalogGateway;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RecordingCatalogGateway catalog() {
        return (RecordingCatalogGateway) catalogGateway;
    }

    private long deliveryMethodId;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE discount_redemptions, order_items, seller_order_links, "
            + "payment_events, payments, orders, discount_codes, cart_items, carts, "
            + "delivery_methods RESTART IDENTITY CASCADE");
        catalog().refuseReservations = false;
        catalog().reservedOrders.clear();

        deliveryMethodId = transactionTemplate.execute(status ->
            deliveryMethodRepository.save(DeliveryMethod.of("Post", null, 40_000L, 0))).getId();
    }

    /** A guest basket holding {@code quantity} of the one variant catalog knows about. */
    private UUID basket(int quantity) {
        UUID token = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            Cart cart = cartRepository.save(Cart.forGuest(token));
            cartItemRepository.save(CartItem.of(cart.getId(), 4L, VARIANT, quantity, 500_000L));
        });
        return token;
    }

    private CheckoutRequest request(String discountCode, String idempotencyKey) {
        return new CheckoutRequest("Ali", "Rezai", "09121234567", "Tehran", "Tehran",
            "Somewhere 12", "1234567890", deliveryMethodId, discountCode, idempotencyKey);
    }

    private OrderResponse checkout(UUID token, CheckoutRequest request) {
        return transactionTemplate.execute(status ->
            checkoutService.checkout(CartOwner.guest(token), request));
    }

    private DiscountCode discount(Consumer<DiscountCode> configure) {
        return transactionTemplate.execute(status -> {
            DiscountCode code = DiscountCode.of("SAVE10", DiscountType.FIXED, 100_000L);
            configure.accept(code);
            return discountCodeRepository.save(code);
        });
    }

    private <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(tasks.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream()
                .map(task -> pool.submit(() -> {
                    startLine.await();
                    return task.call();
                }))
                .toList();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    @DisplayName("a guest checkout writes an order, its items, and empties the basket")
    void placesTheOrder() {
        UUID token = basket(2);

        OrderResponse order = checkout(token, request(null, null));

        assertThat(order.traceCode()).hasSize(10);
        assertThat(order.subtotal()).isEqualTo(1_000_000L);
        assertThat(order.total()).isEqualTo(1_040_000L);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(orderItemRepository.findByOrderIdOrderByIdAsc(order.id())).hasSize(1);
        assertThat(catalog().reservedOrders).containsExactly(order.id());
        assertThat(cartRepository.findByCartToken(token)).isEmpty();
    }

    @Test
    @DisplayName("a failed reservation leaves nothing behind at all")
    void reservationFailureRollsEverythingBack() {
        // The order, its items and the discount redemption are all written before the stock is
        // held. If any of them survived a refused reservation, the shop would have an order it
        // cannot fulfil and a code with a use spent on nothing.
        UUID token = basket(2);
        discount(code -> code.setUsageLimit(1));
        catalog().refuseReservations = true;

        assertThatThrownBy(() -> checkout(token, request("SAVE10", "key-rollback")))
            .isInstanceOf(BusinessRuleException.class);

        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(discountRedemptionRepository.count()).isZero();
        assertThat(discountCodeRepository.findByCodeIgnoreCase("SAVE10").orElseThrow()
            .getTimesUsed()).isZero();
        // And the shopper still has their basket to try again with.
        assertThat(cartRepository.findByCartToken(token)).isPresent();
    }

    @Test
    @DisplayName("the discount is spent exactly once, against the order that used it")
    void discountIsRecordedAgainstTheOrder() {
        UUID token = basket(2);
        DiscountCode code = discount(c -> c.setUsageLimit(5));

        OrderResponse order = checkout(token, request("save10", null));

        assertThat(order.discountAmount()).isEqualTo(100_000L);
        assertThat(order.discountCode()).isEqualTo("SAVE10");
        assertThat(order.total()).isEqualTo(1_000_000L - 100_000L + 40_000L);

        assertThat(discountCodeRepository.findById(code.getId()).orElseThrow().getTimesUsed())
            .isEqualTo(1);
        assertThat(discountRedemptionRepository.findByOrderId(order.id()))
            .get()
            .satisfies(redemption -> assertThat(redemption.getAmount()).isEqualTo(100_000L));
    }

    @Test
    @DisplayName("two tabs submitting one checkout place one order, not two")
    void concurrentReplayPlacesOneOrder() throws Exception {
        // The case the idempotency key exists for. Without the advisory lock both transactions
        // pass the "have I seen this key?" check and the second dies on the unique index - a 500
        // on exactly the retry that was meant to be safe.
        UUID token = basket(2);
        AtomicInteger placed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        List<Callable<String>> attempts = List.of(
            attempt(token, "one-key", placed, refused),
            attempt(token, "one-key", placed, refused));
        List<String> traceCodes = inParallel(attempts);

        assertThat(orderRepository.count())
            .as("one submission, one order")
            .isEqualTo(1);
        assertThat(placed.get()).isEqualTo(2);
        assertThat(refused.get()).isZero();
        // Both callers are told about the same order, so neither browser shows a failure.
        assertThat(traceCodes.getFirst()).isEqualTo(traceCodes.getLast());
        assertThat(catalog().reservedOrders).hasSize(1);
    }

    @Test
    @DisplayName("a replay long afterwards still returns the original order")
    void sequentialReplay() {
        UUID token = basket(2);
        OrderResponse first = checkout(token, request(null, "key-late"));

        // The basket is gone now, so a genuine second checkout would fail with "empty basket".
        // Returning the original is what makes the retry safe rather than merely harmless.
        OrderResponse replay = checkout(token, request(null, "key-late"));

        assertThat(replay.traceCode()).isEqualTo(first.traceCode());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(catalog().reservedOrders).hasSize(1);
    }

    @Test
    @DisplayName("different keys from one basket cannot both succeed")
    void differentKeysDoNotBothPlaceOrders() {
        UUID token = basket(2);
        checkout(token, request(null, "key-a"));

        // The basket became an order and was deleted with it. A second checkout has nothing to buy.
        assertThatThrownBy(() -> checkout(token, request(null, "key-b")))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("basket is empty");

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an order is found by the code the customer quotes, however they type it")
    void tracking() {
        UUID token = basket(1);
        OrderResponse placed = checkout(token, request(null, null));

        assertThat(checkoutService.byTraceCode(placed.traceCode().toLowerCase()).id())
            .isEqualTo(placed.id());
    }

    @Test
    @DisplayName("trace codes are unique across many orders")
    void traceCodesAreUnique() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            codes.add(checkout(basket(1), request(null, null)).traceCode());
        }
        assertThat(codes).doesNotHaveDuplicates();
    }

    private Callable<String> attempt(UUID token, String key,
                                     AtomicInteger placed, AtomicInteger refused) {
        return () -> {
            try {
                String traceCode = checkout(token, request(null, key)).traceCode();
                placed.incrementAndGet();
                return traceCode;
            } catch (RuntimeException e) {
                refused.incrementAndGet();
                return null;
            }
        };
    }
}
