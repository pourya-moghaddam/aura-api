package com.aura.search.index;

/**
 * Indexing failed for a reason worth retrying or worth a human seeing.
 *
 * <p>Deliberately unchecked and deliberately thrown: the binder's retry and dead-letter handling
 * are what stand between a transient Elasticsearch outage and a product silently missing from
 * search. Swallowing it here would make the consumer look healthy while the index drifted.
 */
public class IndexingException extends RuntimeException {

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
