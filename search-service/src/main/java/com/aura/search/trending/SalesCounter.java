package com.aura.search.trending;

import com.aura.common.events.OrderPaidEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * How many of each product have been paid for, kept in Redis.
 *
 * <p><strong>Why not in the Elasticsearch document.</strong> The indexer owns those documents: it
 * replaces them wholesale on every {@code ProductChanged} and relies on external versioning to
 * decide what is newer. A second writer incrementing a field inside the same document would either
 * lose its counts on the next product edit — the replacement carries no {@code salesCount} — or
 * force the indexer into scripted partial updates, which cannot carry an external version and
 * would give up the ordering guarantee that stops stale events overwriting fresh data. Trading a
 * correct index for a popularity number is a bad trade, and the plan puts trending in Redis for
 * exactly this reason.
 *
 * <p>A sorted set, so "the twenty best sellers" is one range query rather than a scan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesCounter {

    private static final String SALES_KEY = "search:trending:sales";
    private static final String SEEN_PREFIX = "search:trending:seen:";

    /**
     * How long a processed event is remembered. Long enough to cover a broker replaying a
     * partition after an outage, short enough that the keys do not accumulate forever.
     */
    private static final Duration DEDUPLICATION_WINDOW = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    /**
     * Records a paid order.
     *
     * <p>Deduplicated on the event id, because Kafka delivers at least once and a redelivery would
     * otherwise inflate a product's popularity every time a consumer restarted mid-batch. Unlike
     * the index, there is no version here to make a repeat harmless — an increment applied twice
     * is simply wrong, and nothing downstream would ever notice.
     */
    public void record(OrderPaidEvent event) {
        Boolean firstTime = redis.opsForValue()
            .setIfAbsent(SEEN_PREFIX + event.eventId(), "1", DEDUPLICATION_WINDOW);

        if (!Boolean.TRUE.equals(firstTime)) {
            log.debug("Ignoring a repeat of OrderPaid {}", event.eventId());
            return;
        }

        for (OrderPaidEvent.Line line : event.lines()) {
            if (line.productId() == null || line.quantity() == null) {
                continue;
            }
            // By quantity, so one order of ten counts as ten. Counting orders instead would make a
            // product bought once in bulk look less popular than one bought twice singly.
            redis.opsForZSet().incrementScore(SALES_KEY, String.valueOf(line.productId()),
                line.quantity());
        }

        log.debug("Counted {} line(s) from order {}", event.lines().size(), event.orderId());
    }

    /** The best sellers, most sold first. */
    public List<Long> topProducts(int limit) {
        Set<String> ids = redis.opsForZSet().reverseRange(SALES_KEY, 0, limit - 1L);

        return ids == null ? List.of()
            : ids.stream().map(Long::valueOf).toList();
    }

    public long salesOf(long productId) {
        Double score = redis.opsForZSet().score(SALES_KEY, String.valueOf(productId));
        return score == null ? 0 : score.longValue();
    }
}
