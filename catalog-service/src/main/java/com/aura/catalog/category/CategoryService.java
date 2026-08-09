package com.aura.catalog.category;

import com.aura.catalog.category.dto.CategoryRequest;
import com.aura.catalog.category.dto.CategoryResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The category tree — requirement 4.
 *
 * <p>Paths are the only tricky part. A category's path is its ancestors' ids plus its own, which
 * means it cannot be written until the id exists; and reparenting has to move an entire subtree,
 * not just the node that moved.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    /**
     * A guard against pathological trees rather than a product requirement. ltree itself allows far
     * deeper, but a category tree this deep is a data-entry mistake, and unbounded depth makes the
     * breadcrumb and menu UI unbuildable.
     */
    private static final int MAX_DEPTH = 6;

    private final CategoryRepository categoryRepository;

    // --- reads ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CategoryResponse> listTree() {
        List<Category> all = categoryRepository.findAll();

        Map<Long, List<Category>> byParent = all.stream()
            .filter(c -> c.getParentId() != null)
            .collect(Collectors.groupingBy(Category::getParentId));

        // Built in memory from a single query rather than one query per level: the whole tree is
        // small and this is the storefront's navigation, requested constantly.
        return all.stream()
            .filter(c -> c.getParentId() == null)
            .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
            .map(root -> buildSubtree(root, byParent))
            .toList();
    }

    private CategoryResponse buildSubtree(Category category, Map<Long, List<Category>> byParent) {
        List<CategoryResponse> children = byParent.getOrDefault(category.getId(), List.of()).stream()
            .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
            .map(child -> buildSubtree(child, byParent))
            .toList();

        return CategoryResponse.flat(category).withChildren(children);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listLeaves() {
        return categoryRepository.findLeaves().stream().map(CategoryResponse::flat).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(long id) {
        return CategoryResponse.flat(require(id));
    }

    @Transactional(readOnly = true)
    public CategoryResponse getBySlug(String slug) {
        return CategoryResponse.flat(categoryRepository.findBySlug(slug)
            .orElseThrow(() -> ResourceNotFoundException.of("Category", slug)));
    }

    /** Ids of a category and everything beneath it — what a category page filters products by. */
    @Transactional(readOnly = true)
    public List<Long> subtreeIds(long categoryId) {
        Category category = require(categoryId);
        return categoryRepository.findSubtree(category.getPath()).stream().map(Category::getId).toList();
    }

    // --- writes --------------------------------------------------------------------------------

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException("category-slug-taken",
                "A category with slug '" + request.slug() + "' already exists.");
        }

        Category parent = request.parentId() == null ? null : require(request.parentId());
        int depth = parent == null ? 0 : parent.getDepth() + 1;

        if (depth > MAX_DEPTH) {
            throw new BusinessRuleException("category-too-deep",
                "Categories may be nested at most " + MAX_DEPTH + " levels deep.");
        }

        Category category = Category.of(
            request.parentId(), request.name(), request.slug(), depth, request.sortOrder());
        if (request.isActive() != null) {
            category.setActive(request.isActive());
        }

        Category saved = categoryRepository.saveAndFlush(category);

        // The path contains this row's own id, so it cannot be known before the insert. Flush above
        // forces the id to be generated; this second write completes the row.
        String path = parent == null
            ? String.valueOf(saved.getId())
            : parent.getPath() + "." + saved.getId();
        categoryRepository.assignPath(saved.getId(), path, depth);
        saved.setPath(path);

        return CategoryResponse.flat(saved);
    }

    @Transactional
    public CategoryResponse update(long id, CategoryRequest request) {
        Category category = require(id);

        categoryRepository.findBySlug(request.slug())
            .filter(other -> !other.getId().equals(id))
            .ifPresent(other -> {
                throw new ConflictException("category-slug-taken",
                    "A category with slug '" + request.slug() + "' already exists.");
            });

        category.setName(request.name());
        category.setSlug(request.slug());
        category.setSortOrder(request.sortOrder());
        if (request.isActive() != null) {
            category.setActive(request.isActive());
        }

        // Renaming never touches paths — that is the whole reason path labels are ids rather than
        // slugs. Only a parent change moves anything.
        if (!java.util.Objects.equals(category.getParentId(), request.parentId())) {
            reparent(category, request.parentId());
        }

        return CategoryResponse.flat(categoryRepository.save(category));
    }

    /**
     * Moves a category and everything beneath it.
     *
     * <p>Two failure modes are guarded here. Moving a category under its own descendant creates a
     * cycle — the tree stops being a tree and every subtree query either loops or silently returns
     * nonsense. And moving a deep subtree under an already-deep parent can push descendants past
     * the depth limit, which the naive check on the moved node alone would miss.
     */
    private void reparent(Category category, Long newParentId) {
        Category newParent = newParentId == null ? null : require(newParentId);

        if (newParent != null && isSelfOrDescendant(category, newParent)) {
            throw new BusinessRuleException("category-cycle",
                "A category cannot be moved beneath itself.");
        }

        String oldPath = category.getPath();
        int oldDepth = category.getDepth();
        int newDepth = newParent == null ? 0 : newParent.getDepth() + 1;

        int deepestDescendant = categoryRepository.findSubtree(oldPath).stream()
            .mapToInt(Category::getDepth).max().orElse(oldDepth);
        if (deepestDescendant - oldDepth + newDepth > MAX_DEPTH) {
            throw new BusinessRuleException("category-too-deep",
                "Moving this category would nest its children more than " + MAX_DEPTH + " levels deep.");
        }

        String newPath = newParent == null
            ? String.valueOf(category.getId())
            : newParent.getPath() + "." + category.getId();

        category.setParentId(newParentId);
        category.setDepth(newDepth);
        category.setPath(newPath);
        categoryRepository.saveAndFlush(category);

        // Descendants keep their relative position; only the prefix changes. One statement, so the
        // tree is never observable in a half-moved state.
        categoryRepository.rewriteSubtreePaths(oldPath, newPath);
    }

    private boolean isSelfOrDescendant(Category category, Category candidate) {
        // Cheaper than a query: a descendant's path is prefixed by its ancestor's.
        return candidate.getId().equals(category.getId())
            || candidate.getPath().startsWith(category.getPath() + ".");
    }

    @Transactional
    public void delete(long id) {
        Category category = require(id);

        // ON DELETE RESTRICT on the self-reference would refuse this anyway, but a clear message
        // beats a foreign-key violation surfacing as a 500.
        if (category.getChildCount() > 0) {
            throw new BusinessRuleException("category-has-children",
                "Delete or move this category's subcategories first.");
        }

        categoryRepository.delete(category);
    }

    // --- shared --------------------------------------------------------------------------------

    /**
     * Resolves a category that a product may be attached to.
     *
     * <p>The leaf rule is enforced by a database trigger as well; this exists so the seller gets a
     * readable message naming the problem rather than a constraint violation.
     */
    @Transactional(readOnly = true)
    public Category requireLeaf(long categoryId) {
        Category category = require(categoryId);
        if (!category.isLeaf()) {
            throw new BusinessRuleException("category-not-leaf",
                "Products can only be added to a category with no subcategories.");
        }
        if (!category.isActive()) {
            throw new BusinessRuleException("category-inactive",
                "This category is not currently accepting products.");
        }
        return category;
    }

    @Transactional(readOnly = true)
    public Category require(long id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Category", id));
    }

    /** Ancestors of a category, root first — the basis of field inheritance. */
    @Transactional(readOnly = true)
    public List<Category> ancestorsOf(Category category) {
        return new ArrayList<>(categoryRepository.findAncestors(category.getPath()));
    }
}
