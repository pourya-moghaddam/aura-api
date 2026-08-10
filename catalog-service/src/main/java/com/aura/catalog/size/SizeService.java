package com.aura.catalog.size;

import com.aura.catalog.category.CategoryService;
import com.aura.catalog.size.dto.SizeRequest;
import com.aura.catalog.size.dto.SizeResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SizeService {

    private final SizeRepository sizeRepository;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public List<SizeResponse> listAll() {
        return sizeRepository.findAllByOrderBySortOrderAscNameAsc().stream()
            .map(SizeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SizeResponse> listActive() {
        return sizeRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc().stream()
            .map(SizeResponse::from).toList();
    }

    /**
     * What the seller's variant form should offer for a given category: sizes scoped anywhere up
     * this category's ancestry, plus every global size.
     */
    @Transactional(readOnly = true)
    public List<SizeResponse> listForCategory(long categoryId) {
        // Resolves-or-throws, so an unknown category is a 404 rather than a silently empty list
        // that looks like "this category has no sizes".
        categoryService.require(categoryId);
        return sizeRepository.findAvailableForCategory(categoryId).stream()
            .map(SizeResponse::from).toList();
    }

    @Transactional
    public SizeResponse create(SizeRequest request) {
        if (request.categoryId() != null) {
            categoryService.require(request.categoryId());
        }
        requireNameAvailableInScope(request.name(), request.categoryId(), null);

        Size size = Size.of(request.name().trim(), request.categoryId(), request.sortOrder());
        if (request.isActive() != null) {
            size.setActive(request.isActive());
        }
        return SizeResponse.from(sizeRepository.save(size));
    }

    @Transactional
    public SizeResponse update(long id, SizeRequest request) {
        Size size = require(id);
        if (request.categoryId() != null) {
            categoryService.require(request.categoryId());
        }
        requireNameAvailableInScope(request.name(), request.categoryId(), id);

        size.setName(request.name().trim());
        size.setCategoryId(request.categoryId());
        size.setSortOrder(request.sortOrder());
        if (request.isActive() != null) {
            size.setActive(request.isActive());
        }
        return SizeResponse.from(sizeRepository.save(size));
    }

    @Transactional
    public void delete(long id) {
        Size size = require(id);

        if (sizeRepository.isUsedByAnyVariant(id)) {
            throw new BusinessRuleException("size-in-use",
                "This size is used by existing product variants. Deactivate it instead.");
        }
        sizeRepository.delete(size);
    }

    /**
     * Scoped uniqueness, mirroring the database's {@code UNIQUE NULLS NOT DISTINCT (name,
     * category_id)}: "L" can exist once globally and once under a specific category, but not twice
     * in the same scope.
     */
    private void requireNameAvailableInScope(String name, Long categoryId, Long excludingId) {
        sizeRepository.findByNameAndScope(name.trim(), categoryId)
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("size-name-taken",
                    "A size named '" + existing.getName() + "' already exists in this scope.");
            });
    }

    @Transactional(readOnly = true)
    public Size require(long id) {
        return sizeRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Size", id));
    }
}
