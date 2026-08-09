package com.aura.catalog.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Category> findByParentIdOrderBySortOrderAscNameAsc(Long parentId);

    List<Category> findByParentIdIsNullOrderBySortOrderAscNameAsc();

    /**
     * Every category at or beneath the given path — the storefront's "browse this section"
     * question, answered by one GIST-indexed scan instead of a recursive CTE.
     */
    @Query(value = "SELECT * FROM categories WHERE path <@ CAST(:path AS ltree) ORDER BY path",
        nativeQuery = true)
    List<Category> findSubtree(@Param("path") String path);

    /**
     * Every ancestor of the given path, including the category itself.
     *
     * <p>This is what makes field inheritance work: a field defined on "Clothing" applies to
     * "Clothing > Men > Shirts" because Clothing is in this result for the shirt's path.
     */
    @Query(value = "SELECT * FROM categories WHERE path @> CAST(:path AS ltree) ORDER BY depth",
        nativeQuery = true)
    List<Category> findAncestors(@Param("path") String path);

    /** Leaves only — what a seller may actually attach a product to. */
    @Query("SELECT c FROM Category c WHERE c.childCount = 0 AND c.isActive = true ORDER BY c.name")
    List<Category> findLeaves();

    /**
     * Rewrites an entire subtree's paths in one statement when a category is reparented.
     *
     * <p>Done in SQL rather than by loading and saving each descendant: a subtree can be large, and
     * this has to be atomic — a half-rewritten tree has categories whose ancestors disagree about
     * where they are.
     */
    @Modifying
    @Query(value = """
        UPDATE categories
        SET path  = CAST(:newPrefix AS ltree) || subpath(path, nlevel(CAST(:oldPrefix AS ltree))),
            depth = nlevel(CAST(:newPrefix AS ltree)) + nlevel(path)
                    - nlevel(CAST(:oldPrefix AS ltree)) - 1
        WHERE path <@ CAST(:oldPrefix AS ltree)
        """, nativeQuery = true)
    void rewriteSubtreePaths(@Param("oldPrefix") String oldPrefix, @Param("newPrefix") String newPrefix);

    @Modifying
    @Query(value = "UPDATE categories SET path = CAST(:path AS ltree), depth = :depth WHERE id = :id",
        nativeQuery = true)
    void assignPath(@Param("id") Long id, @Param("path") String path, @Param("depth") int depth);
}
