package com.aura.order.order;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.order.dto.SellerOrderResponse;
import com.aura.order.outbox.OutboxRepository;
import com.aura.order.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two sellers, one basket — requirement 8's hidden constraint, against a real database.
 *
 * <p>The unit tests prove the rules with the repositories mocked, which means they also prove that
 * the <em>queries</em> were never run. This is where the filtering that keeps one seller out of
 * another's business is actually executed, and where the outbox row is shown to commit in the same
 * transaction as the status change rather than merely being asked for.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SellerOrderService.class, OutboxWriter.class, SellerOrderIT.Stubs.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerOrderIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long ALICE = 7L;
    private static final long BOB = 8L;

    @TestConfiguration
    static class Stubs {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired
    private SellerOrderService sellerOrderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long orderId;
    private Long aliceItemId;
    private Long bobItemId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE outbox, payment_events, payments, discount_redemptions, "
            + "order_items, seller_order_links, orders RESTART IDENTITY CASCADE");

        transactionTemplate.executeWithoutResult(status -> {
            Order order = new Order();
            order.setTraceCode(TraceCodes.generate());
            order.setBuyerFirstName("Ali");
            order.setBuyerLastName("Rezai");
            order.setBuyerPhone("+989121234567");
            order.setAddressSnapshot(Map.of("city", "Tehran"));
            order.setPostalCode("1234567890");
            order.setDeliveryName("Post");
            order.setDeliveryFee(40_000L);
            order.setSubtotal(1_300_000L);
            order.setTotal(1_340_000L);
            order.setDiscountAmount(0L);
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setPaidAt(OffsetDateTime.now());
            order.setCreatedAt(OffsetDateTime.now());
            order.setUpdatedAt(order.getCreatedAt());
            orderId = orderRepository.save(order).getId();

            aliceItemId = orderItemRepository.save(OrderItem.of(orderId, 1L, 11L, ALICE,
                "Alice's Shirt", Map.of("color", "Navy"), 500_000L, 2)).getId();
            bobItemId = orderItemRepository.save(OrderItem.of(orderId, 2L, 22L, BOB,
                "Bob's Hat", Map.of("size", "L"), 300_000L, 1)).getId();
        });
    }

    private SellerOrderResponse advance(long sellerId, long itemId, FulfillmentStatus next) {
        return transactionTemplate.execute(status ->
            sellerOrderService.advance(sellerId, orderId, itemId, next));
    }

    /** The nth outbox payload, parsed. */
    private com.fasterxml.jackson.databind.JsonNode payload(int index) {
        try {
            return new ObjectMapper().readTree(outboxRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getId()))
                .toList().get(index).getPayload());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("outbox payload is not JSON", e);
        }
    }

    private FulfillmentStatus orderStatus() {
        return orderRepository.findById(orderId).orElseThrow().getDerivedStatus();
    }

    @Test
    @DisplayName("each seller's listing shows their own lines and nobody else's")
    void listingIsScopedToTheSeller() {
        SellerOrderResponse alice = sellerOrderService
            .list(ALICE, PageRequest.of(0, 20)).getContent().getFirst();
        SellerOrderResponse bob = sellerOrderService
            .list(BOB, PageRequest.of(0, 20)).getContent().getFirst();

        assertThat(alice.items()).extracting(i -> i.productName())
            .containsExactly("Alice's Shirt");
        assertThat(bob.items()).extracting(i -> i.productName())
            .containsExactly("Bob's Hat");

        // And the totals are each seller's own, not the order's 1,300,000.
        assertThat(alice.sellerTotal()).isEqualTo(1_000_000L);
        assertThat(bob.sellerTotal()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("an order with several of one seller's lines appears once in their list")
    void oneRowPerOrderNotPerLine() {
        // A join instead of an EXISTS subquery silently multiplies the page, and the seller sees
        // the same order three times.
        transactionTemplate.executeWithoutResult(status ->
            orderItemRepository.save(OrderItem.of(orderId, 3L, 33L, ALICE,
                "Alice's Scarf", Map.of(), 200_000L, 1)));

        assertThat(sellerOrderService.list(ALICE, PageRequest.of(0, 20)).getTotalElements())
            .isEqualTo(1);
        assertThat(sellerOrderService.get(ALICE, orderId).items()).hasSize(2);
    }

    @Test
    @DisplayName("a seller with nothing in the order sees nothing and cannot open it")
    void strangersSeeNothing() {
        assertThat(sellerOrderService.list(99L, PageRequest.of(0, 20))).isEmpty();
        assertThatThrownBy(() -> sellerOrderService.get(99L, orderId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an unpaid order is on nobody's screen")
    void unpaidOrdersAreInvisible() {
        jdbcTemplate.update("UPDATE orders SET payment_status = 'PENDING' WHERE id = ?", orderId);

        assertThat(sellerOrderService.list(ALICE, PageRequest.of(0, 20))).isEmpty();
        assertThatThrownBy(() -> sellerOrderService.get(ALICE, orderId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a seller cannot advance another seller's line")
    void cannotAdvanceAnotherSellersLine() {
        assertThatThrownBy(() -> advance(ALICE, bobItemId, FulfillmentStatus.PROCESSING))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(orderItemRepository.findById(bobItemId).orElseThrow().getFulfillmentStatus())
            .isEqualTo(FulfillmentStatus.PENDING);
    }

    @Test
    @DisplayName("the order is only as far along as its least-advanced line")
    void derivedStatusFollowsTheSlowest() {
        advance(ALICE, aliceItemId, FulfillmentStatus.PROCESSING);
        assertThat(orderStatus())
            .as("Bob has not started")
            .isEqualTo(FulfillmentStatus.PENDING);

        advance(BOB, bobItemId, FulfillmentStatus.PROCESSING);
        assertThat(orderStatus()).isEqualTo(FulfillmentStatus.PROCESSING);

        advance(ALICE, aliceItemId, FulfillmentStatus.SHIPPED);
        assertThat(orderStatus())
            .as("Bob's hat is still being packed")
            .isEqualTo(FulfillmentStatus.PROCESSING);

        advance(BOB, bobItemId, FulfillmentStatus.SHIPPED);
        assertThat(orderStatus()).isEqualTo(FulfillmentStatus.SHIPPED);

        advance(ALICE, aliceItemId, FulfillmentStatus.DELIVERED);
        advance(BOB, bobItemId, FulfillmentStatus.DELIVERED);
        assertThat(orderStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("one seller cancelling leaves the order following the rest")
    void oneCancellationDoesNotCancelTheOrder() {
        // The buyer is still waiting on Bob. Showing "cancelled" would tell them their order is
        // off when most of it is on its way.
        advance(ALICE, aliceItemId, FulfillmentStatus.CANCELLED);
        advance(BOB, bobItemId, FulfillmentStatus.PROCESSING);

        assertThat(orderStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
    }

    @Test
    @DisplayName("every line cancelled does cancel the order")
    void allCancelledCancelsTheOrder() {
        advance(ALICE, aliceItemId, FulfillmentStatus.CANCELLED);
        advance(BOB, bobItemId, FulfillmentStatus.CANCELLED);

        assertThat(orderStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("the buyer's notification is written in the same transaction as the change")
    void outboxRowCommitsWithTheChange() {
        advance(ALICE, aliceItemId, FulfillmentStatus.PROCESSING);

        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll().getFirst().getPartitionKey())
            .isEqualTo(String.valueOf(orderId));
        // Read as JSON rather than matched as text: the column is jsonb and PostgreSQL returns it
        // reformatted, so a substring assertion here tests Postgres's whitespace, not the payload.
        com.fasterxml.jackson.databind.JsonNode payload = payload(0);
        assertThat(payload.path("newStatus").asText()).isEqualTo("PROCESSING");
        assertThat(payload.path("previousStatus").asText()).isEqualTo("PENDING");
        assertThat(payload.path("traceCode").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a refused move leaves no notification behind")
    void refusedMoveWritesNothing() {
        // The buyer must never be told about a change that did not happen.
        assertThatThrownBy(() -> advance(ALICE, aliceItemId, FulfillmentStatus.DELIVERED))
            .isInstanceOf(BusinessRuleException.class);

        assertThat(outboxRepository.count()).isZero();
        assertThat(orderItemRepository.findById(aliceItemId).orElseThrow().getFulfillmentStatus())
            .isEqualTo(FulfillmentStatus.PENDING);
    }

    @Test
    @DisplayName("a repeated move notifies the buyer once")
    void repeatDoesNotNotifyTwice() {
        advance(ALICE, aliceItemId, FulfillmentStatus.PROCESSING);
        advance(ALICE, aliceItemId, FulfillmentStatus.PROCESSING);

        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("each step notifies once, in the order it happened")
    void eachStepNotifiesOnce() {
        advance(ALICE, aliceItemId, FulfillmentStatus.PROCESSING);
        advance(ALICE, aliceItemId, FulfillmentStatus.SHIPPED);
        advance(ALICE, aliceItemId, FulfillmentStatus.DELIVERED);

        // Ordered by id, which is insertion order, which is what the publisher drains in - so the
        // buyer is never told "delivered" before "shipped".
        assertThat(outboxRepository.count()).isEqualTo(3);
        assertThat(payload(0).path("newStatus").asText()).isEqualTo("PROCESSING");
        assertThat(payload(1).path("newStatus").asText()).isEqualTo("SHIPPED");
        assertThat(payload(2).path("newStatus").asText()).isEqualTo("DELIVERED");
    }
}
