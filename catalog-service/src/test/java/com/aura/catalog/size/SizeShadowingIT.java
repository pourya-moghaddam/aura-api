package com.aura.catalog.size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sizes a seller is offered, and which of two same-named ones wins.
 *
 * <p>Against a real database because the answer is a SQL window function over an ltree containment
 * scan — {@code nlevel}, {@code @>} and {@code ROW_NUMBER} are all PostgreSQL, and a mocked
 * repository would just return whatever the test handed it, proving nothing about the query that
 * actually runs.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SizeShadowingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SizeRepository sizeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long clothing;
    private long men;
    private long shirts;
    private long footwear;

    @BeforeEach
    void buildTree() {
        jdbcTemplate.execute("TRUNCATE sizes, categories RESTART IDENTITY CASCADE");

        clothing = category("Clothing", "clothing", null);
        men = category("Men", "men", clothing);
        shirts = category("Shirts", "shirts", men);
        footwear = category("Footwear", "footwear", null);
    }

    private long category(String name, String slug, Long parentId) {
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO categories (parent_id, name, slug, depth, sort_order)
            VALUES (?, ?, ?, 0, 0) RETURNING id
            """, Long.class, parentId, name, slug);

        String path = parentId == null
            ? String.valueOf(id)
            : jdbcTemplate.queryForObject(
                "SELECT path::text FROM categories WHERE id = ?", String.class, parentId) + "." + id;

        jdbcTemplate.update("UPDATE categories SET path = CAST(? AS ltree), depth = nlevel(CAST(? AS ltree)) - 1 "
            + "WHERE id = ?", path, path, id);
        return id;
    }

    private long size(String name, Long categoryId, int sortOrder) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO sizes (name, category_id, sort_order, is_active)
            VALUES (?, ?, ?, TRUE) RETURNING id
            """, Long.class, name, categoryId, sortOrder);
    }

    private List<String> namesFor(long categoryId) {
        return sizeRepository.findAvailableForCategory(categoryId).stream().map(Size::getName).toList();
    }

    @Test
    @DisplayName("a global size is offered everywhere")
    void globalSizeIsInherited() {
        size("L", null, 0);

        assertThat(namesFor(shirts)).containsExactly("L");
        assertThat(namesFor(footwear)).containsExactly("L");
    }

    @Test
    @DisplayName("an ancestor's size reaches a leaf without being redefined there")
    void ancestorSizeIsInherited() {
        size("M", clothing, 0);

        assertThat(namesFor(shirts)).containsExactly("M");
    }

    @Test
    @DisplayName("a size scoped to an unrelated branch is not offered")
    void unrelatedBranchIsExcluded() {
        size("42", footwear, 0);

        assertThat(namesFor(shirts)).isEmpty();
    }

    @Test
    @DisplayName("a category-scoped size shadows the global one of the same name")
    void scopedShadowsGlobal() {
        // The whole point of redefining it: "L" under Clothing means something different from the
        // global "L", and offering both would put the same label in the picker twice.
        long global = size("L", null, 0);
        long scoped = size("L", clothing, 0);

        List<Size> offered = sizeRepository.findAvailableForCategory(shirts);

        assertThat(offered).hasSize(1);
        assertThat(offered.getFirst().getId()).isEqualTo(scoped);
        assertThat(offered.getFirst().getId()).isNotEqualTo(global);
        assertThat(offered.getFirst().getCategoryId()).isEqualTo(clothing);
    }

    @Test
    @DisplayName("the deepest definition wins when several ancestors define the same name")
    void deepestAncestorWins() {
        size("L", null, 0);
        size("L", clothing, 0);
        long deepest = size("L", men, 0);

        List<Size> offered = sizeRepository.findAvailableForCategory(shirts);

        assertThat(offered).hasSize(1);
        assertThat(offered.getFirst().getId()).isEqualTo(deepest);
    }

    @Test
    @DisplayName("shadowing applies per name, leaving other sizes alone")
    void shadowingIsPerName() {
        size("L", null, 0);
        size("L", clothing, 1);
        size("XL", null, 2);

        assertThat(namesFor(shirts)).containsExactly("L", "XL");
    }

    @Test
    @DisplayName("a size shadowed for one branch is still the global one elsewhere")
    void shadowingDoesNotLeakAcrossBranches() {
        long global = size("L", null, 0);
        size("L", clothing, 0);

        List<Size> forFootwear = sizeRepository.findAvailableForCategory(footwear);

        assertThat(forFootwear).hasSize(1);
        assertThat(forFootwear.getFirst().getId()).isEqualTo(global);
    }

    @Test
    @DisplayName("shadowing ignores case, since the picker shows the label not the bytes")
    void shadowingIsCaseInsensitive() {
        size("l", null, 0);
        long scoped = size("L", clothing, 0);

        List<Size> offered = sizeRepository.findAvailableForCategory(shirts);

        assertThat(offered).hasSize(1);
        assertThat(offered.getFirst().getId()).isEqualTo(scoped);
    }

    @Test
    @DisplayName("an inactive size is never offered, shadowing or not")
    void inactiveSizesExcluded() {
        size("L", null, 0);
        long scoped = size("L", clothing, 0);
        jdbcTemplate.update("UPDATE sizes SET is_active = FALSE WHERE id = ?", scoped);

        // The global one is not shadowed by something that cannot be picked.
        assertThat(namesFor(shirts)).containsExactly("L");
    }

    @Test
    @DisplayName("results stay in display order")
    void ordering() {
        size("XL", null, 2);
        size("S", null, 0);
        size("M", null, 1);

        assertThat(namesFor(shirts)).containsExactly("S", "M", "XL");
    }
}
