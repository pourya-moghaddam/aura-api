package com.aura.catalog.category.dto;

import com.aura.catalog.category.Category;

import java.util.List;

/**
 * @param isLeaf  what the seller UI needs: only leaves may hold products
 * @param children populated when returning a tree, null when returning a flat list
 */
public record CategoryResponse(
    Long id,
    Long parentId,
    String name,
    String slug,
    String path,
    int depth,
    int sortOrder,
    boolean isActive,
    boolean isLeaf,
    List<CategoryResponse> children
) {

    public static CategoryResponse flat(Category category) {
        return new CategoryResponse(
            category.getId(), category.getParentId(), category.getName(), category.getSlug(),
            category.getPath(), category.getDepth(), category.getSortOrder(),
            category.isActive(), category.isLeaf(), null);
    }

    public CategoryResponse withChildren(List<CategoryResponse> children) {
        return new CategoryResponse(id, parentId, name, slug, path, depth, sortOrder,
            isActive, isLeaf, children);
    }
}
