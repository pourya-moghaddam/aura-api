package com.aura.order.link;

import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import com.aura.order.config.OrderLinkProperties;
import com.aura.order.delivery.DeliveryMethod;
import com.aura.order.delivery.DeliveryMethodRepository;
import com.aura.order.delivery.DeliveryMethodService;
import com.aura.order.discount.DiscountService;
import com.aura.order.link.dto.CreateOrderLinkRequest;
import com.aura.order.link.dto.OrderLinkResponse;
import com.aura.order.order.CheckoutService;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.OrderSource;
import com.aura.order.order.PaymentStatus;
import com.aura.order.payment.PaymentEventRepository;
import com.aura.order.payment.PaymentRepository;
import com.aura.order.payment.PaymentService;
import com.aura.order.payment.dto.PaymentStartResponse;
import com.aura.order.payment.zarinpal.MockZarinpalClient;
import com.aura.order.payment.zarinpal.ZarinpalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A seller's order link, end to end against a real database.
 *
 * <p>The case that needs a real database is single use. A link is a bearer credential for someone
 * with no account, and "used once" has to survive two taps arriving together — which is a row lock
 * doing its job, and cannot be demonstrated with a mock.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SellerOrderLinkService.class, CheckoutService.class, PaymentService.class,
    DeliveryMethodService.class, DiscountService.class, MockZarinpalClient.class,
    SellerOrderLinkIT.Stubs.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerOrderLinkIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long ALICE = 7L;
    private static final long BOB = 8L;
    private static final long ALICE_VARIANT = 11L;
    private static final long BOB_VARIANT = 22L;

    @TestConfiguration
    static class Stubs {

        @Bean
        CatalogGateway catalogGateway() {
            return new StubCatalog();
        }

        @Bean
        ZarinpalProperties zarinpalProperties() {
            return new ZarinpalProperties("m", "https://sandbox.zarinpal.com",
                "http://cb", "http://shop/result", "mock");
        }

        @Bean
        OrderLinkProperties orderLinkProperties() {
            return new OrderLinkProperties(Duration.ofHours(48), "http://shop/pay");
        }
    }

    /** Two sellers, one variant each, so ownership can actually be got wrong. */
    static class StubCatalog implements CatalogGateway {

        final AtomicInteger reservations = new AtomicInteger();

        @Override
        public Map<Long, VariantSnapshot> snapshotsFor(Collection<Long> variantIds) {
            return Map.of(
                ALICE_VARIANT, new VariantSnapshot(ALICE_VARIANT, 1L, ALICE, "Alice's Shirt",
                    "shirt", "Navy", "L", 500_000L, true, 50),
                BOB_VARIANT, new VariantSnapshot(BOB_VARIANT, 2L, BOB, "Bob's Hat",
                    "hat", null, "M", 300_000L, true, 50));
        }

        @Override
        public void reserveStock(long orderId, List<StockLine> lines) {
            reservations.incrementAndGet();
        }

        @Override
        public void releaseStock(long orderId) {
        }

        @Override
        public void commitStock(long orderId) {
        }
    }

    @Autowired
    private SellerOrderLinkService service;

    @Autowired
    private SellerOrderLinkRepository linkRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private DeliveryMethodRepository deliveryMethodRepository;

    @Autowired
    private CatalogGateway catalogGateway;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long deliveryMethodId;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE outbox, payment_events, payments, discount_redemptions, "
            + "order_items, seller_order_links, orders, delivery_methods RESTART IDENTITY CASCADE");
        ((StubCatalog) catalogGateway).reservations.set(0);

        deliveryMethodId = transactionTemplate.execute(status ->
            deliveryMethodRepository.save(DeliveryMethod.of("Post", null, 40_000L, 0))).getId();
    }

    private CreateOrderLinkRequest request(long variantId, int quantity) {
        return new CreateOrderLinkRequest("Ali", "Rezai", "09121234567", "Tehran", "Tehran",
            "Somewhere 12", "1234567890", deliveryMethodId,
            List.of(new CreateOrderLinkRequest.Line(variantId, quantity)));
    }

    private OrderLinkResponse create(long sellerId, long variantId, int quantity) {
        return transactionTemplate.execute(status ->
            service.create(sellerId, request(variantId, quantity)));
    }

    private String tokenOf(OrderLinkResponse response) {
        return response.url().substring("http://shop/pay/".length());
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
    @DisplayName("a composed order is written, priced from catalog, with its stock held")
    void createsTheOrder() {
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 2);

        assertThat(link.total()).isEqualTo(2 * 500_000L + 40_000L);
        assertThat(link.url()).startsWith("http://shop/pay/");
        assertThat(((StubCatalog) catalogGateway).reservations.get()).isEqualTo(1);

        assertThat(orderRepository.findByTraceCode(link.traceCode()))
            .get()
            .satisfies(order -> {
                assertThat(order.getSource()).isEqualTo(OrderSource.SELLER_LINK);
                assertThat(order.getUserId()).as("the buyer has no account").isNull();
                assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            });
    }

    @Test
    @DisplayName("the raw token is nowhere in the database")
    void tokenIsStoredHashedOnly() {
        // A leaked database must not hand out working links.
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);
        String rawToken = tokenOf(link);

        assertThat(linkRepository.findAll().getFirst().getTokenHash())
            .isEqualTo(LinkTokens.hash(rawToken));
        Integer matches = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM seller_order_links WHERE token_hash = ?", Integer.class, rawToken);
        assertThat(matches).isZero();
    }

    @Test
    @DisplayName("a seller cannot compose an order from someone else's catalogue")
    void cannotSellAnotherSellersProduct() {
        assertThatThrownBy(() -> create(ALICE, BOB_VARIANT, 1))
            .hasMessageContaining("only contain your own products");

        assertThat(orderRepository.count()).isZero();
        assertThat(linkRepository.count()).isZero();
        assertThat(((StubCatalog) catalogGateway).reservations.get()).isZero();
    }

    @Test
    @DisplayName("the buyer opens the link and sees what they are paying for")
    void buyerSeesTheOrder() {
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 2);

        var order = service.view(tokenOf(link));

        assertThat(order.traceCode()).isEqualTo(link.traceCode());
        assertThat(order.items()).hasSize(1);
        assertThat(order.total()).isEqualTo(1_040_000L);
    }

    @Test
    @DisplayName("opening the link repeatedly does not spend it")
    void viewingIsFree() {
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);

        for (int i = 0; i < 5; i++) {
            service.view(tokenOf(link));
        }

        assertThat(linkRepository.findAll().getFirst().isUsed()).isFalse();
    }

    @Test
    @DisplayName("paying spends the link and starts one payment")
    void payingSpendsTheLink() {
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);

        PaymentStartResponse payment = transactionTemplate.execute(status ->
            service.pay(tokenOf(link)));

        assertThat(payment.redirectUrl()).contains("/pg/StartPay/");
        assertThat(linkRepository.findAll().getFirst().isUsed()).isTrue();
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a spent link is indistinguishable from a wrong guess")
    void spentLinkLooksUnknown() {
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);
        String token = tokenOf(link);
        transactionTemplate.execute(status -> service.pay(token));

        assertThatThrownBy(() -> service.view(token))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> service.pay(token)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an expired link stops working")
    void expiredLink() {
        // The stock behind it is held from the moment the order is written, which is why the link
        // does not live for a month.
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);
        jdbcTemplate.update("UPDATE seller_order_links SET expires_at = NOW() - INTERVAL '1 hour'");

        assertThatThrownBy(() -> service.view(tokenOf(link)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("two taps on one link start one payment, not two")
    void singleUseUnderARace() throws Exception {
        // The buyer taps twice, or forwards the link to their spouse who opens it at the same
        // moment. Without the row lock both find it unused and two payments are started for one
        // order - and the second sends the buyer to a gateway for money already being taken.
        OrderLinkResponse link = create(ALICE, ALICE_VARIANT, 1);
        String token = tokenOf(link);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(token, started, refused),
            attempt(token, started, refused)));

        assertThat(started.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(1);
        assertThat(paymentRepository.count())
            .as("one link, one payment attempt")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown token is a 404 and touches nothing")
    void unknownToken() {
        assertThatThrownBy(() -> service.view("not-a-real-token"))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    @DisplayName("a seller's own list shows their links without the tokens")
    void listing() {
        create(ALICE, ALICE_VARIANT, 1);
        create(ALICE, ALICE_VARIANT, 2);
        create(BOB, BOB_VARIANT, 1);

        List<OrderLinkResponse> alices = service.listFor(ALICE);

        assertThat(alices).hasSize(2);
        assertThat(alices).allSatisfy(link -> assertThat(link.url()).isNull());
        assertThat(service.listFor(BOB)).hasSize(1);
    }

    private Callable<Void> attempt(String token, AtomicInteger started, AtomicInteger refused) {
        return () -> {
            try {
                transactionTemplate.execute(status -> service.pay(token));
                started.incrementAndGet();
            } catch (RuntimeException e) {
                refused.incrementAndGet();
            }
            return null;
        };
    }
}
