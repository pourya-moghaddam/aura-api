package com.aura.search.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.ReindexResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.IndexDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds the index behind the alias and flips to it, without downtime.
 *
 * <p>The plan calls this part of the deliverable rather than an afterthought, and it is right: an
 * analysis change cannot be applied to an open index, so the first time the mapping is wrong — and
 * it will be, because the Persian chain is the sort of thing you tune after seeing real searches —
 * the only route forward is a rebuild. Discovering that on the day is how a shop ends up with
 * search switched off for an afternoon.
 *
 * <p>The sequence, and why it is three steps rather than two:
 *
 * <ol>
 *   <li><strong>Copy.</strong> Build {@code products_v2} from the current definition and reindex
 *       into it, preserving each document's external version.</li>
 *   <li><strong>Flip.</strong> Move the alias in a single atomic call, so no request ever sees
 *       neither index or both.</li>
 *   <li><strong>Catch up.</strong> Copy again. The first pass takes minutes on a real catalogue,
 *       and everything the live consumer wrote during it went to the <em>old</em> index — those
 *       documents would otherwise be silently missing. External versioning is what makes a second
 *       pass safe: it can only overwrite something older, so anything that arrived after the flip
 *       survives, and the conflicts it reports are the expected outcome rather than a problem.</li>
 * </ol>
 *
 * <p>Nothing is deleted. The old index stays until an operator removes it, because the one thing
 * worse than a bad mapping is a bad mapping with nothing to roll back to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private static final Pattern GENERATION = Pattern.compile("_v(\\d+)$");

    private final ElasticsearchClient client;
    private final IndexDefinition definition;
    private final SearchProperties properties;

    public ReindexResult rebuild() {
        try {
            String current = currentIndex();
            String next = properties.indexName(generationOf(current) + 1);

            log.info("Reindexing {} into {}", current, next);
            create(next);

            long copied = copy(current, next);
            flip(current, next);
            long caughtUp = copy(current, next);

            log.info("Reindex complete: {} into {}, {} copied, {} caught up after the flip",
                current, next, copied, caughtUp);

            return new ReindexResult(current, next, copied, caughtUp);

        } catch (IOException e) {
            throw new BusinessRuleException("reindex-failed",
                "The reindex could not be completed: " + e.getMessage());
        }
    }

    /** The single index the alias points at today. */
    private String currentIndex() throws IOException {
        Map<String, ?> indices = client.indices().getAlias(a -> a.name(properties.alias())).result();

        if (indices.isEmpty()) {
            throw new BusinessRuleException("no-index",
                "There is no index behind the alias to rebuild from.");
        }
        if (indices.size() > 1) {
            // Two indices means a previous reindex was interrupted between its steps. Guessing
            // which to copy from could silently drop half the catalogue.
            throw new BusinessRuleException("ambiguous-alias",
                "The alias points at more than one index: " + indices.keySet()
                    + ". Resolve that before reindexing.");
        }
        return indices.keySet().iterator().next();
    }

    private int generationOf(String index) {
        Matcher matcher = GENERATION.matcher(index);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    private void create(String index) throws IOException {
        if (client.indices().exists(e -> e.index(index)).value()) {
            // Left over from an interrupted attempt. Reusing it would mix documents built under
            // two different mappings, which is the exact confusion a reindex exists to end.
            throw new BusinessRuleException("index-exists",
                "Index " + index + " already exists. Delete it before reindexing.");
        }
        client.indices().create(CreateIndexRequest.of(builder -> builder
            .index(index)
            .withJson(new StringReader(definition.json()))));
    }

    private long copy(String from, String to) throws IOException {
        // Refresh the source first. A reindex scrolls the source index, and anything written but
        // not yet refreshed is invisible to a scroll - so without this the copy silently misses
        // whatever the consumer indexed in the last second, and on a quiet cluster that can be
        // everything. It reported zero documents copied and no error at all.
        client.indices().refresh(r -> r.index(from));

        ReindexResponse response = client.reindex(r -> r
            .source(s -> s.index(from))
            // External versioning carried across, so the new index remembers how new each document
            // is and a later event can still be judged against it.
            .dest(d -> d.index(to).versionType(VersionType.External))
            // A conflict means the destination already holds something at least as new, which on
            // the second pass is the ordinary case rather than a failure.
            .conflicts(Conflicts.Proceed)
            .refresh(true)
            .waitForCompletion(true));

        return response.created() == null ? 0 : response.created();
    }

    /** One call, so no request ever sees neither index or both. */
    private void flip(String from, String to) throws IOException {
        client.indices().updateAliases(u -> u
            .actions(a -> a.remove(r -> r.index(from).alias(properties.alias())))
            .actions(a -> a.add(add -> add.index(to).alias(properties.alias()))));

        log.info("Alias '{}' now points at {}", properties.alias(), to);
    }

    /**
     * @param copied    documents in the first pass
     * @param caughtUp  documents the live consumer wrote during it, recovered by the second. A
     *                  non-zero figure here is the reindex having done its job, not a warning.
     */
    public record ReindexResult(String from, String to, long copied, long caughtUp) {

        public Set<String> indices() {
            return Set.of(from, to);
        }
    }
}
