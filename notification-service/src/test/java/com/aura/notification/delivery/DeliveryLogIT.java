package com.aura.notification.delivery;

import com.aura.notification.sms.SmsReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery log against a real Postgres.
 *
 * <p>A mock cannot show any of this. The claim relies on a unique constraint to decide who sends,
 * and on the losing insert's transaction being finished with before the winner is read back — a
 * detail that compiles perfectly and fails only when a real database marks the transaction
 * rollback-only.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DeliveryLog.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryLogIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PHONE = "+989121234567";

    @Autowired
    private DeliveryLog deliveryLog;

    @Autowired
    private SmsDeliveryRepository repository;

    @BeforeEach
    void reset() {
        // Nothing rolls back here - the whole point is that these transactions commit - so each
        // test has to start from an empty table or the row counts below measure the class, not the
        // behaviour.
        repository.deleteAll();
    }

    @Test
    @DisplayName("the first claim wins and the second is turned away")
    void aSettledEventIsNotClaimedTwice() {
        UUID event = UUID.randomUUID();

        SmsDelivery first = deliveryLog.claim(event, NotificationKind.OTP, PHONE).orElseThrow();
        deliveryLog.recordSent(first.getId(), new SmsReceipt("p-1", BigDecimal.ONE), 42, null);

        assertThat(deliveryLog.claim(event, NotificationKind.OTP, PHONE)).isEmpty();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a claim that collides can still read the winning row")
    void collidingClaimDoesNotPoisonItsTransaction() {
        // The failure this test exists for: a constraint violation marks its transaction
        // rollback-only, so looking the winner up in that same transaction throws at commit with a
        // message about an unexpected rollback rather than about the duplicate. It compiles, it
        // passes against a mock, and it fails the first time two messages arrive for one event.
        UUID event = UUID.randomUUID();
        deliveryLog.claim(event, NotificationKind.OTP, PHONE).orElseThrow();

        Optional<SmsDelivery> second = deliveryLog.claim(event, NotificationKind.OTP, PHONE);

        // Still PENDING, so the second claim gets the row rather than being refused - a message
        // whose first attempt died mid-flight must still go out.
        assertThat(second).isPresent();
        assertThat(second.get().getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("a permanently failed event is never attempted again")
    void failedEventsAreNotRetried() {
        UUID event = UUID.randomUUID();
        SmsDelivery claim = deliveryLog.claim(event, NotificationKind.OTP, PHONE).orElseThrow();
        deliveryLog.recordFailed(claim.getId(), "sms.ir status=-1 message=bad template");

        assertThat(deliveryLog.claim(event, NotificationKind.OTP, PHONE)).isEmpty();
    }

    @Test
    @DisplayName("two threads racing the same event produce one claim")
    void concurrentClaimsProduceOneSend() throws Exception {
        // Two consumer threads on the same partition, or a rebalance mid-batch. A read-then-insert
        // would let both through and the shopper would get two codes.
        UUID event = UUID.randomUUID();

        List<Optional<SmsDelivery>> results = inParallel(List.of(
            () -> deliveryLog.claim(event, NotificationKind.OTP, PHONE),
            () -> deliveryLog.claim(event, NotificationKind.OTP, PHONE)));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(results).allMatch(Optional::isPresent);
        assertThat(results.getFirst().orElseThrow().getId())
            .isEqualTo(results.getLast().orElseThrow().getId());
    }

    @Test
    @DisplayName("a retryable failure leaves the row claimable and counts the attempt")
    void attemptsAccumulate() {
        UUID event = UUID.randomUUID();
        SmsDelivery claim = deliveryLog.claim(event, NotificationKind.OTP, PHONE).orElseThrow();

        deliveryLog.recordAttempt(claim.getId(), "connection reset");
        deliveryLog.recordAttempt(claim.getId(), "connection reset");

        SmsDelivery reloaded = repository.findByEventId(event).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(2);
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        // A row that reads SENT after three attempts is a different operational story from one
        // that worked first time, and the counter is the only place that difference survives.
        assertThat(deliveryLog.claim(event, NotificationKind.OTP, PHONE)).isPresent();
    }

    @Test
    @DisplayName("the one-time code is nowhere in the row")
    void theCodeIsNeverStored() {
        UUID event = UUID.randomUUID();
        SmsDelivery claim = deliveryLog.claim(event, NotificationKind.OTP, PHONE).orElseThrow();
        deliveryLog.recordSent(claim.getId(), new SmsReceipt("p-2", BigDecimal.ZERO), 42, null);

        SmsDelivery reloaded = repository.findByEventId(event).orElseThrow();
        // A code written to a table outlives its validity window, and a database dump then holds
        // working credentials. Only the template id is kept.
        assertThat(reloaded.getBody()).isNull();
        assertThat(reloaded.getTemplateId()).isEqualTo(42);
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
}
