package com.aura.catalog.field.dto;

import com.aura.catalog.field.Field;
import com.aura.catalog.field.FieldDataType;

import java.util.List;

/**
 * @param inherited true when this field comes from an ancestor rather than from the category being
 *                  asked about. The control panel needs this to grey out editing — a field shown
 *                  under "Shirts" but owned by "Clothing" must be edited on "Clothing", or the
 *                  admin will think they are changing one category and silently change many.
 * @param values    the allowed values, empty for data types that do not enumerate them.
 */
public record FieldResponse(
    Long id,
    Long categoryId,
    String name,
    String slug,
    FieldDataType dataType,
    boolean isRequired,
    boolean isFilterable,
    int sortOrder,
    boolean inherited,
    List<FieldValueResponse> values
) {

    public static FieldResponse from(Field field, List<FieldValueResponse> values, boolean inherited) {
        return new FieldResponse(field.getId(), field.getCategoryId(), field.getName(),
            field.getSlug(), field.getDataType(), field.isRequired(), field.isFilterable(),
            field.getSortOrder(), inherited, values);
    }
}
