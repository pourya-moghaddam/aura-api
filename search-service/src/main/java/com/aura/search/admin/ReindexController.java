package com.aura.search.admin;

import com.aura.common.security.AdminOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rebuilding the search index, by hand and on purpose.
 *
 * <p>Admin-only and never automatic. A reindex is cheap on a small catalogue and expensive on a
 * large one, and the moment to run it is a judgement about traffic that belongs to whoever is
 * watching the shop rather than to a schedule.
 */
@RestController
@RequiredArgsConstructor
public class ReindexController {

    private final ReindexService reindexService;

    @PostMapping("/api/control/search/reindex")
    @AdminOnly
    public ResponseEntity<ReindexService.ReindexResult> reindex() {
        return ResponseEntity.ok(reindexService.rebuild());
    }
}
