package com.aura.catalog.product;

import com.aura.catalog.product.dto.VariantSnapshotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What order-service is told about variants it wants to sell.
 *
 * <p>A service rather than a controller reaching straight for the repository — which is what the
 * ArchUnit rule caught. Two things belong here rather than in the web layer: the read runs inside
 * a declared transaction, and the ceiling on how many ids one request may ask about is a rule
 * about the domain, not about HTTP.
 */
@Service
@RequiredArgsConstructor
public class VariantSnapshotService {

    /**
     * Bounded so one request cannot ask for the whole catalogue. Well above any real cart — the
     * point is that the list comes from a basket, not from a crawler walking the id space.
     */
    static final int MAX_IDS = 200;

    private final VariantSnapshotRepository variantSnapshotRepository;

    @Transactional(readOnly = true)
    public List<VariantSnapshotResponse> snapshotsFor(List<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }

        List<Long> bounded = variantIds.stream().distinct().limit(MAX_IDS).toList();

        return variantSnapshotRepository.findSnapshots(bounded).stream()
            .map(VariantSnapshotResponse::from)
            .toList();
    }
}
