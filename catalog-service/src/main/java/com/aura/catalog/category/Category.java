package com.aura.catalog.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Plain id rather than a {@code @ManyToOne} to the parent. A self-referencing association makes
     * every category load a potential walk up the tree, and nothing here needs the parent object —
     * only its id and, for path maintenance, its path.
     */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 120)
    private String slug;

    /**
     * Materialised ancestor path, e.g. {@code 1.5.12}.
     *
     * <p>Mapped as String with an explicit cast on write. Hibernate has no ltree type, and without
     * the transformer Postgres refuses to assign varchar to ltree — the insert fails with a type
     * error that reads like a mapping bug rather than a missing cast.
     */
    @Column(name = "path", columnDefinition = "ltree")
    @ColumnTransformer(write = "?::ltree")
    private String path;

    @Column(nullable = false)
    private int depth;

    /**
     * Maintained by a database trigger, never written from here. Read-only to JPA so an accidental
     * setter call cannot fight the trigger.
     */
    @Column(name = "child_count", insertable = false, updatable = false)
    private int childCount;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** A category with no children is where products are allowed to attach. */
    public boolean isLeaf() {
        return childCount == 0;
    }

    public static Category of(Long parentId, String name, String slug, int depth, int sortOrder) {
        Category category = new Category();
        category.parentId = parentId;
        category.name = name;
        category.slug = slug;
        category.depth = depth;
        category.sortOrder = sortOrder;
        category.createdAt = OffsetDateTime.now();
        category.updatedAt = category.createdAt;
        return category;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
