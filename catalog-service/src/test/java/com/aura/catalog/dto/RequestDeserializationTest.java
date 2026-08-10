package com.aura.catalog.dto;

import com.aura.catalog.category.dto.CategoryRequest;
import com.aura.catalog.color.dto.ColorRequest;
import com.aura.catalog.size.dto.SizeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every optional property really is optional.
 *
 * <p>The {@code ArchitectureTest} rule stops a primitive component from being reintroduced; this
 * pins the behaviour that rule exists to protect, so a failure reads as "omitting sortOrder broke"
 * rather than as an abstract complaint about field types.
 */
class RequestDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("category deserializes without sortOrder and defaults it to 0")
    void categoryWithoutSortOrder() throws Exception {
        CategoryRequest request = objectMapper.readValue(
            """
            {"name":"Shirts","slug":"shirts"}
            """, CategoryRequest.class);

        assertThat(request.sortOrder()).isZero();
        assertThat(request.isActive()).isNull();
        assertThat(request.parentId()).isNull();
    }

    @Test
    @DisplayName("category keeps an explicit sortOrder")
    void categoryWithSortOrder() throws Exception {
        CategoryRequest request = objectMapper.readValue(
            """
            {"name":"Shirts","slug":"shirts","sortOrder":7}
            """, CategoryRequest.class);

        assertThat(request.sortOrder()).isEqualTo(7);
    }

    @Test
    @DisplayName("colour deserializes without sortOrder")
    void colorWithoutSortOrder() throws Exception {
        ColorRequest request = objectMapper.readValue(
            """
            {"name":"Red","hexCode":"#FF0000"}
            """, ColorRequest.class);

        assertThat(request.sortOrder()).isZero();
    }

    @Test
    @DisplayName("size deserializes without sortOrder or categoryId")
    void sizeWithoutOptionalFields() throws Exception {
        SizeRequest request = objectMapper.readValue(
            """
            {"name":"L"}
            """, SizeRequest.class);

        assertThat(request.sortOrder()).isZero();
        assertThat(request.categoryId()).isNull();
    }
}
