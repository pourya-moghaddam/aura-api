package com.aura.catalog.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The category listing runs a native query, so its sort reaches PostgreSQL as written. These pin
 * the two things that follow from that: the names have to be columns, and they have to come from
 * here rather than from the request.
 */
class ProductSortTest {

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    @DisplayName("every sort names real columns, never entity properties")
    void sortsUseColumnNames(ProductSort sort) {
        // "createdAt" reaching native SQL is what produced `ORDER BY p.createdat` and a 500 on
        // every category page. Snake case is the tell that these are columns.
        assertThat(sort.toPageable(0, 24).getSort())
            .allSatisfy(order -> assertThat(order.getProperty())
                .doesNotContainPattern("[A-Z]")
                .isIn("created_at", "min_price", "name", "id"));
    }

    @Test
    @DisplayName("newest first is the default ordering, tie-broken by id")
    void newestFirst() {
        Sort sort = ProductSort.NEWEST.toPageable(0, 24).getSort();

        assertThat(sort.getOrderFor("created_at")).isNotNull()
            .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.DESC);
        // Without the tie-break, two products created in the same millisecond can swap places
        // between page 1 and page 2 - one shown twice, the other never.
        assertThat(sort.getOrderFor("id")).isNotNull();
    }

    @Test
    @DisplayName("price sorts read the denormalised range, not a join over variants")
    void priceSortsUseTheDenormalisedColumn() {
        assertThat(ProductSort.PRICE_ASC.toPageable(0, 24).getSort().getOrderFor("min_price"))
            .isNotNull()
            .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.ASC);
        assertThat(ProductSort.PRICE_DESC.toPageable(0, 24).getSort().getOrderFor("min_price"))
            .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("an absurd page size is clamped rather than honoured")
    void sizeIsClamped() {
        // Otherwise ?size=1000000 is a free denial of service against the category page.
        assertThat(ProductSort.NEWEST.toPageable(0, 1_000_000).getPageSize()).isEqualTo(100);
        assertThat(ProductSort.NEWEST.toPageable(0, 0).getPageSize()).isEqualTo(1);
        assertThat(ProductSort.NEWEST.toPageable(0, -5).getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative page number is treated as the first page")
    void negativePageClamped() {
        Pageable pageable = ProductSort.NEWEST.toPageable(-3, 24);

        assertThat(pageable.getPageNumber()).isZero();
    }
}
