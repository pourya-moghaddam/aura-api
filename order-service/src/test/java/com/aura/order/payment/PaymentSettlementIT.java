package com.aura.order.payment;

import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import com.aura.order.order.Order;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.PaymentStatus;
import com.aura.order.payment.dto.PaymentStartResponse;
import com.aura.order.payment.zarinpal.MockZarinpalClient;
import com.aura.order.payment.zarinpal.ZarinpalClient;
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

/**
 * Settling a payment against a real database.
 *
 * <p>The case worth running is the one that happens on a bad night rather than a good one: the
 * shopper's callback and the reconciliation sweep reaching the same payment at the same instant.
 * Both find it pending, both verify, and without the row lock both settle it — committing the
 * stock twice and, on a real gateway, reading the second 101 as a failure.
 *
 * <p>Zarinpal is the mock client, which reproduces the one behaviour that matters here: the first
 * verify answers 100 and every one after it answers 101.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentService.class, MockZarinpalClient.class, PaymentSettlementIT.Stubs.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentSettlementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Stubs {

        @Bean
        CatalogGateway catalogGateway() {
            return new CountingCatalogGateway();
        }

        @Bean
        ZarinpalProperties zarinpalProperties() {
            return new ZarinpalProperties("m", "https://sandbox.zarinpal.com",
                "http://cb", "http://shop/result", "mock");
        }
    }

    /** Counts settlements so double-committing is visible rather than merely harmless. */
    static class CountingCatalogGateway implements CatalogGateway {

        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public Map<Long, VariantSnapshot> snapshotsFor(Collection<Long> variantIds) {
            return Map.of();
        }

        @Override
        public void reserveStock(long orderId, List<StockLine> lines) {
        }

        @Override
        public void releaseStock(long orderId) {
            releases.incrementAndGet();
        }

        @Override
        public void commitStock(long orderId) {
            commits.incrementAndGet();
        }
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ZarinpalClient zarinpalClient;

    @Autowired
    private CatalogGateway catalogGateway;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CountingCatalogGateway catalog() {
        return (CountingCatalogGateway) catalogGateway;
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE payment_events, payments, discount_redemptions, "
            + "order_items, seller_order_links, orders RESTART IDENTITY CASCADE");
        catalog().commits.set(0);
        catalog().releases.set(0);
    }

    private Order order() {
        return transactionTemplate.execute(status -> {
            Order order = new Order();
            order.setTraceCode(com.aura.order.order.TraceCodes.generate());
            order.setBuyerFirstName("Ali");
            order.setBuyerLastName("Rezai");
            order.setBuyerPhone("+989121234567");
            order.setAddressSnapshot(Map.of("city", "Tehran"));
            order.setPostalCode("1234567890");
            order.setDeliveryName("Post");
            order.setDeliveryFee(40_000L);
            order.setSubtotal(1_000_000L);
            order.setTotal(1_040_000L);
            order.setDiscountAmount(0L);
            order.setCreatedAt(java.time.OffsetDateTime.now());
            order.setUpdatedAt(order.getCreatedAt());
            return orderRepository.save(order);
        });
    }

    private String startPayment(Order order) {
        PaymentStartResponse response = transactionTemplate.execute(status ->
            paymentService.start(order.getTraceCode()));
        return response.authority();
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
    @DisplayName("a verified callback pays the order and commits the stock once")
    void callbackSettlesTheOrder() {
        Order order = order();
        String authority = startPayment(order);

        transactionTemplate.executeWithoutResult(status ->
            paymentService.settleCallback(authority, "OK"));

        Order settled = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(settled.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(settled.getPaidAt()).isNotNull();
        assertThat(catalog().commits.get()).isEqualTo(1);

        Payment payment = paymentRepository.findByAuthority(authority).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getRefId()).isNotNull();
    }

    @Test
    @DisplayName("the callback and the reconciliation sweep racing settle the order exactly once")
    void callbackAndReconciliationDoNotBothSettle() throws Exception {
        // The night this matters: a slow callback arriving as the sweep picks the same payment up.
        // Without the row lock both verify, both commit the stock, and the second reads 101.
        Order order = order();
        String authority = startPayment(order);
        // Backdate the attempt so the sweep considers it stale rather than waiting ten minutes.
        jdbcTemplate.update("UPDATE payments SET created_at = NOW() - INTERVAL '1 hour'");

        inParallel(List.of(
            (Callable<Void>) () -> {
                transactionTemplate.executeWithoutResult(s ->
                    paymentService.settleCallback(authority, "OK"));
                return null;
            },
            () -> {
                transactionTemplate.executeWithoutResult(s ->
                    paymentService.reconcile(Duration.ofMinutes(10), 50));
                return null;
            }));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.PAID);
        assertThat(catalog().commits.get())
            .as("the stock must leave the shelf once, not twice")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a lost callback is settled by the sweep alone")
    void reconciliationSettlesALostCallback() {
        // The shopper paid and closed the browser on the bank's page. Nothing else will ever tell
        // the shop what happened.
        Order order = order();
        startPayment(order);
        jdbcTemplate.update("UPDATE payments SET created_at = NOW() - INTERVAL '1 hour'");

        int settled = transactionTemplate.execute(status ->
            paymentService.reconcile(Duration.ofMinutes(10), 50));

        assertThat(settled).isEqualTo(1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("a payment younger than the threshold is left alone")
    void freshPaymentsAreNotDisturbed() {
        // The shopper is still typing their card details. Verifying now would fail and cancel the
        // order out from under them.
        Order order = order();
        startPayment(order);

        int settled = transactionTemplate.execute(status ->
            paymentService.reconcile(Duration.ofMinutes(10), 50));

        assertThat(settled).isZero();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("a shopper refreshing the return page changes nothing")
    void repeatedCallbacksAreIdempotent() {
        Order order = order();
        String authority = startPayment(order);

        for (int i = 0; i < 5; i++) {
            transactionTemplate.executeWithoutResult(status ->
                paymentService.settleCallback(authority, "OK"));
        }

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.PAID);
        assertThat(catalog().commits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancelled payment releases the stock and can be attempted again")
    void cancellationReleasesAndAllowsARetry() {
        Order order = order();
        String first = startPayment(order);

        transactionTemplate.executeWithoutResult(status ->
            paymentService.settleCallback(first, "NOK"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.CANCELLED);
        assertThat(catalog().releases.get()).isEqualTo(1);

        // And the order is not payable again, because its stock is gone.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transactionTemplate.execute(status -> paymentService.start(order.getTraceCode())))
            .hasMessageContaining("no longer be paid");
    }

    @Test
    @DisplayName("every exchange with the gateway is kept")
    void eventsAreKept() {
        Order order = order();
        String authority = startPayment(order);
        transactionTemplate.executeWithoutResult(status ->
            paymentService.settleCallback(authority, "OK"));

        Payment payment = paymentRepository.findByAuthority(authority).orElseThrow();
        assertThat(paymentEventRepository.findByPaymentIdOrderByIdAsc(payment.getId()))
            .extracting(PaymentEvent::getType)
            .containsExactly("REQUEST", "CALLBACK", "VERIFY");
    }

    @Test
    @DisplayName("an authority is unique, so two attempts cannot collide")
    void authoritiesAreUnique() {
        Order first = order();
        Order second = order();

        assertThat(startPayment(first)).isNotEqualTo(startPayment(second));
        assertThat(paymentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a paid order refuses a second payment attempt")
    void paidOrdersAreNotPayableAgain() {
        Order order = order();
        String authority = startPayment(order);
        transactionTemplate.executeWithoutResult(status ->
            paymentService.settleCallback(authority, "OK"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transactionTemplate.execute(status -> paymentService.start(order.getTraceCode())))
            .hasMessageContaining("already been paid");
    }
}
