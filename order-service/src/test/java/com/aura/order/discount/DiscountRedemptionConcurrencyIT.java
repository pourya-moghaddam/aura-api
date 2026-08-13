package com.aura.order.discount;

import com.aura.common.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One code, one use, several shoppers.
 *
 * <p>The unit tests prove the rules; this proves the rules hold when two transactions apply them
 * at the same instant, which is the only time a usage limit is ever actually tested. A mocked
 * repository would let both callers read {@code times_used = 0} and both succeed — the exact bug —
 * and would do so silently, since neither call is wrong on its own.
 *
 * <p>Real PostgreSQL, because {@code SELECT ... FOR UPDATE} is the mechanism under test and no
 * in-memory substitute has it.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiscountService.class)
// Each test drives its own transactions across threads; the usual test-managed transaction would
// both hide the concurrency and roll back the evidence.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiscountRedemptionConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiscountService discountService;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Autowired
    private DiscountRedemptionRepository discountRedemptionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE discount_redemptions, order_items, seller_order_links, "
            + "payment_events, payments, orders, discount_codes RESTART IDENTITY CASCADE");
    }

    /** A code with a usage limit and, optionally, a per-account one. */
    private DiscountCode code(Integer usageLimit, Integer perUserLimit) {
        return transactionTemplate.execute(status -> {
            DiscountCode discount = DiscountCode.of("SAVE10", DiscountType.FIXED, 10_000L);
            discount.setUsageLimit(usageLimit);
            discount.setPerUserLimit(perUserLimit);
            return discountCodeRepository.save(discount);
        });
    }

    /**
     * A real order row, because the redemption's foreign key insists on one — and so does
     * {@code uq_redemption_order}, which is what makes a repeated redemption of one order
     * impossible rather than merely unlikely.
     */
    private long order(Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO orders (trace_code, user_id, buyer_phone, buyer_first_name, buyer_last_name,
                                address_snapshot, postal_code, delivery_name, delivery_fee,
                                subtotal, total)
            VALUES (?, ?, '09120000000', 'A', 'B', '{}'::jsonb, '1234567890', 'Post', 0, 100000, 100000)
            RETURNING id
            """, Long.class, "T" + java.util.UUID.randomUUID().toString().substring(0, 12), userId);
    }

    /** Releases every task from a barrier so they genuinely race rather than merely interleave. */
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

    private Callable<Void> attempt(long orderId, Long userId,
                                   AtomicInteger succeeded, AtomicInteger refused) {
        return () -> {
            try {
                transactionTemplate.executeWithoutResult(status ->
                    discountService.redeem("SAVE10", 100_000L, userId, orderId));
                succeeded.incrementAndGet();
            } catch (BusinessRuleException e) {
                refused.incrementAndGet();
            } catch (RuntimeException e) {
                // A unique-constraint violation is still a refusal, and still correct - it just
                // arrived from the database rather than from the service.
                refused.incrementAndGet();
            }
            return null;
        };
    }

    @Test
    @DisplayName("two checkouts racing for the last use of a code: exactly one gets it")
    void onlyOneCheckoutGetsTheLastUse() throws Exception {
        code(1, null);
        long first = order(null);
        long second = order(null);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(first, null, succeeded, refused),
            attempt(second, null, succeeded, refused)));

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(1);
        assertThat(discountCodeRepository.findByCodeIgnoreCase("SAVE10").orElseThrow().getTimesUsed())
            .isEqualTo(1);
        assertThat(discountRedemptionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("twenty checkouts racing for five uses: exactly five get one")
    void aLimitedCodeCannotBeOverRedeemedUnderLoad() throws Exception {
        code(5, null);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            attempts.add(attempt(order(null), null, succeeded, refused));
        }
        inParallel(attempts);

        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(15);

        DiscountCode after = discountCodeRepository.findByCodeIgnoreCase("SAVE10").orElseThrow();
        assertThat(after.getTimesUsed()).isEqualTo(5);
        // The counter and the rows have to agree. If they drifted, the shop would either be giving
        // away discounts it never counted or refusing ones it never gave.
        assertThat(discountRedemptionRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("one shopper racing themselves cannot beat a per-account limit")
    void perUserLimitHoldsUnderARace() throws Exception {
        // Two tabs, one account, a code marked "one per customer". Both requests pass the count
        // check before either writes unless the row lock orders them.
        code(null, 1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(order(7L), 7L, succeeded, refused),
            attempt(order(7L), 7L, succeeded, refused),
            attempt(order(7L), 7L, succeeded, refused)));

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(2);
        assertThat(discountRedemptionRepository.countByDiscountCodeIdAndUserId(
            discountCodeRepository.findByCodeIgnoreCase("SAVE10").orElseThrow().getId(), 7L))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a double-submitted checkout redeems its order once, not twice")
    void oneOrderRedeemsOnce() throws Exception {
        // The same order id arriving twice - a retried POST, a double-clicked button. The usage
        // limit would not catch this on an unlimited code; uq_redemption_order does.
        code(null, null);
        long orderId = order(null);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(List.of(
            attempt(orderId, null, succeeded, refused),
            attempt(orderId, null, succeeded, refused)));

        assertThat(discountRedemptionRepository.findByOrderId(orderId)).isPresent();
        assertThat(discountRedemptionRepository.count())
            .as("an order may carry at most one discount")
            .isEqualTo(1);
        assertThat(succeeded.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unlimited code is not serialised into a bottleneck by mistake")
    void unlimitedCodeLetsEveryoneThrough() throws Exception {
        code(null, null);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(attempt(order(null), null, succeeded, refused));
        }
        inParallel(attempts);

        assertThat(succeeded.get()).isEqualTo(8);
        assertThat(refused.get()).isZero();
        assertThat(discountCodeRepository.findByCodeIgnoreCase("SAVE10").orElseThrow().getTimesUsed())
            .isEqualTo(8);
    }

    @Test
    @DisplayName("the amount taken off is recorded, not left to be recomputed later")
    void redemptionRecordsTheAmount() {
        code(null, null);
        long orderId = order(3L);

        long amount = transactionTemplate.execute(status ->
            discountService.redeem("SAVE10", 100_000L, 3L, orderId));

        DiscountRedemption redemption = discountRedemptionRepository.findByOrderId(orderId)
            .orElseThrow();
        assertThat(amount).isEqualTo(10_000L);
        assertThat(redemption.getAmount()).isEqualTo(10_000L);
        assertThat(redemption.getUserId()).isEqualTo(3L);
    }
}
