package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import com.aura.common.events.ProductChangedEvent;
import com.aura.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Writes a product into the index, or takes it out.
 *
 * <p>Everything here turns on <strong>external versioning</strong>. Kafka orders messages within a
 * partition, and every event for one product carries the same partition key, so ordinary delivery
 * is ordered. Redelivery is not: a consumer that fails and retries, or a partition that moves
 * between instances, can present yesterday's document after today's. Elasticsearch is told the
 * product's {@code updated_at} as the version and refuses anything not newer, which turns
 * "impossible to reason about" into "the database says no".
 *
 * <p>That single mechanism also makes the consumer idempotent for free: a duplicate of the event
 * just applied carries the same version and is refused the same way. No dedup table, nothing to
 * keep tidy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexer {

    /** Elasticsearch's code for "you sent me something not newer than what I have". */
    private static final int VERSION_CONFLICT = 409;

    private final ElasticsearchClient client;
    private final SearchProperties properties;

    /**
     * Applies one event.
     *
     * @return true when the index changed, false when the event was stale or a duplicate
     */
    public boolean apply(ProductChangedEvent event) {
        return event.deleted() ? remove(event) : index(event);
    }

    private boolean index(ProductChangedEvent event) {
        ProductDocument document = ProductDocument.from(event);

        try {
            client.index(request -> request
                .index(properties.alias())
                .id(document.id())
                .document(document)
                // The product's own updated_at, not a counter of our own. A version we generated
                // would be meaningless across a reindex, and would not survive this service being
                // restarted with an empty memory.
                .versionType(VersionType.External)
                .version(event.version()));

            log.debug("Indexed product {} at version {}", event.productId(), event.version());
            return true;

        } catch (ElasticsearchException | IOException e) {
            if (isVersionConflict(e)) {
                // Not an error. Either a redelivery of something already applied, or a genuinely
                // older document arriving late - and in both cases the index already holds
                // something at least as new.
                log.debug("Ignored stale event for product {} at version {}",
                    event.productId(), event.version());
                return false;
            }
            throw new IndexingException("Could not index product " + event.productId(), e);
        }
    }

    /**
     * Whether this failure is "you sent me something not newer", in either shape it arrives in.
     *
     * <p>The client reports the same condition two ways depending on the operation: sometimes as a
     * parsed {@link ElasticsearchException} carrying a status, sometimes as the low-level
     * {@code ResponseException} — which extends {@link IOException} and would otherwise be read as
     * an unreachable cluster. That misreading is expensive and invisible: every ordinary stale
     * redelivery would be retried three times and dead-lettered as though Elasticsearch were down,
     * and the dead-letter topic would fill with messages that were never a problem.
     */
    private boolean isVersionConflict(Exception e) {
        if (e instanceof ElasticsearchException elastic) {
            return elastic.status() == VERSION_CONFLICT;
        }
        if (e instanceof org.elasticsearch.client.ResponseException response) {
            return response.getResponse().getStatusLine().getStatusCode() == VERSION_CONFLICT;
        }
        return false;
    }

    private boolean remove(ProductChangedEvent event) {
        try {
            DeleteResponse response = client.delete(request -> request
                .index(properties.alias())
                .id(String.valueOf(event.productId()))
                // Versioned like the write. A delete that arrived before a later edit must not
                // remove the newer document.
                .versionType(VersionType.External)
                .version(event.version()));

            // Deleting something absent does not throw: Elasticsearch records the version and
            // answers "not_found". The desired state is "absent" and it is absent, so this is a
            // success - but nothing changed, and saying otherwise would misreport it.
            if (response.result() == Result.NotFound) {
                log.debug("Product {} was already absent from the index", event.productId());
                return false;
            }

            log.debug("Removed product {} from the index", event.productId());
            return true;

        } catch (ElasticsearchException | IOException e) {
            if (isVersionConflict(e)) {
                log.debug("Ignored stale delete for product {}", event.productId());
                return false;
            }
            throw new IndexingException("Could not delete product " + event.productId(), e);
        }
    }
}
