package com.aura.catalog.inventory;

import com.aura.catalog.inventory.StockReservation.ReservationStatus;
import com.aura.catalog.inventory.dto.ReserveStockRequest;
import com.aura.common.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two shoppers, one last unit.
 *
 * <p>This is the test the plan calls out (§11) and the one that cannot be written with mocks: the
 * thing being verified is that PostgreSQL's {@code FOR UPDATE} serialises two real transactions.
 * A mocked repository would happily let both callers read "1 available" and both succeed, which is
 * precisely the bug — and it would fail only in production, on the busiest day, as a customer
 * being charged for something that is not there.
 *
 * <p>Runs against a real database for the same reason: the row lock, the
 * {@code ck_inventory_not_oversold} constraint and the {@code NULLS NOT DISTINCT} index are all
 * database behaviour, and an in-memory substitute has none of them.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StockReservationService.class)
// Each test drives its own transactions across several threads, so the usual test-managed
// transaction would both hide the concurrency and roll the evidence back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockReservationConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long variantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE stock_reservations, inventory, product_variants, products, "
            + "product_media, product_field_values, field_values, fields, sizes, colors, categories "
            + "RESTART IDENTITY CASCADE");

        Long categoryId = jdbcTemplate.queryForObject("""
            INSERT INTO categories (parent_id, name, slug, depth, sort_order)
            VALUES (NULL, 'Cat', 'cat', 0, 0) RETURNING id
            """, Long.class);
        jdbcTemplate.update("UPDATE categories SET path = CAST(? AS ltree) WHERE id = ?",
            String.valueOf(categoryId), categoryId);

        Long productId = jdbcTemplate.queryForObject("""
            INSERT INTO products (seller_id, category_id, name, slug, status)
            VALUES (1, ?, 'P', 'p', 'ACTIVE') RETURNING id
            """, Long.class, categoryId);

        variantId = jdbcTemplate.queryForObject("""
            INSERT INTO product_variants (product_id, sku, price) VALUES (?, 'SKU-1', 1000) RETURNING id
            """, Long.class, productId);
    }

    private void stock(int onHand) {
        transactionTemplate.executeWithoutResult(status ->
            inventoryRepository.save(Inventory.forVariant(variantId, onHand)));
    }

    private ReserveStockRequest request(long orderId, int quantity) {
        return new ReserveStockRequest(orderId,
            List.of(new ReserveStockRequest.Line(variantId, quantity)));
    }

    /** Runs the callables simultaneously, releasing them from a barrier so they genuinely race. */
    private <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(tasks.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream()
                .map(task -> pool.submit(() -> {
                    startLine.await();
                    return task.call();
                }))
                .toList();

            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    @DisplayName("two orders racing for the last unit: exactly one wins")
    void onlyOneOrderGetsTheLastUnit() throws Exception {
        stock(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(1001L, 1, succeeded, refused),
            attempt(1002L, 1, succeeded, refused)));

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(1);

        Inventory after = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(after.getQuantityReserved()).isEqualTo(1);
        assertThat(after.available()).isZero();
    }

    @Test
    @DisplayName("ten orders racing for three units: exactly three win, and stock is never negative")
    void oversellingIsImpossibleUnderLoad() throws Exception {
        stock(3);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        List<Callable<Void>> attempts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attempts.add(attempt(2000L + i, 1, succeeded, refused));
        }
        inParallel(attempts);

        assertThat(succeeded.get()).isEqualTo(3);
        assertThat(refused.get()).isEqualTo(7);

        Inventory after = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(after.getQuantityReserved()).isEqualTo(3);
        assertThat(after.available()).isZero();
        // The database's own backstop. If the application logic were wrong, this constraint would
        // have refused the write rather than allowing a negative available figure.
        assertThat(after.getQuantityReserved()).isLessThanOrEqualTo(after.getQuantityOnHand());
    }

    @Test
    @DisplayName("the same order retried concurrently holds stock once, not twice")
    void concurrentRetriesOfOneOrderHoldOnce() throws Exception {
        // A double-clicked checkout button sends two identical requests at once. Both may pass the
        // "already held?" check before either writes, so the row lock has to be what settles it.
        stock(10);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(3001L, 2, succeeded, refused),
            attempt(3001L, 2, succeeded, refused)));

        Inventory after = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(after.getQuantityReserved())
            .as("a retried checkout must not consume double the stock")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("committing and releasing concurrently leaves the counters consistent")
    void concurrentSettlementIsConsistent() throws Exception {
        stock(10);
        transactionTemplate.executeWithoutResult(s -> stockReservationService.reserve(request(4001L, 3)));
        transactionTemplate.executeWithoutResult(s -> stockReservationService.reserve(request(4002L, 4)));

        inParallel(List.of(
            (Callable<Void>) () -> {
                transactionTemplate.executeWithoutResult(s -> stockReservationService.commit(4001L));
                return null;
            },
            () -> {
                transactionTemplate.executeWithoutResult(s -> stockReservationService.release(4002L));
                return null;
            }));

        Inventory after = inventoryRepository.findById(variantId).orElseThrow();
        // 3 sold, 4 returned to the shelf: 7 on hand, nothing still held.
        assertThat(after.getQuantityOnHand()).isEqualTo(7);
        assertThat(after.getQuantityReserved()).isZero();
        assertThat(after.available()).isEqualTo(7);
    }

    @Test
    @DisplayName("an expired hold is swept up and the stock becomes buyable again")
    void expirySweepReturnsStock() {
        stock(5);
        transactionTemplate.executeWithoutResult(s -> stockReservationService.reserve(request(5001L, 5)));
        assertThat(inventoryRepository.findById(variantId).orElseThrow().available()).isZero();

        // Backdate the hold rather than waiting fifteen minutes.
        jdbcTemplate.update("UPDATE stock_reservations SET expires_at = NOW() - INTERVAL '1 minute'");

        int released = transactionTemplate.execute(s -> stockReservationService.releaseExpired(100));

        assertThat(released).isEqualTo(1);
        assertThat(inventoryRepository.findById(variantId).orElseThrow().available()).isEqualTo(5);
        assertThat(stockReservationRepository.findByOrderId(5001L))
            .allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("the database refuses to hold more than exists, even if the service were wrong")
    void databaseConstraintIsTheBackstop() {
        // Belt and braces: the application checks availability, and ck_inventory_not_oversold
        // makes the wrong answer unwritable rather than merely unlikely.
        stock(2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(s ->
                jdbcTemplate.update("UPDATE inventory SET quantity_reserved = 5 WHERE variant_id = ?",
                    variantId)))
            .hasMessageContaining("ck_inventory_not_oversold");
    }

    private Callable<Void> attempt(long orderId, int quantity,
                                   AtomicInteger succeeded, AtomicInteger refused) {
        return () -> {
            try {
                transactionTemplate.executeWithoutResult(status ->
                    stockReservationService.reserve(request(orderId, quantity)));
                succeeded.incrementAndGet();
            } catch (BusinessRuleException e) {
                refused.incrementAndGet();
            }
            return null;
        };
    }
}
