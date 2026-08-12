package com.aura.catalog.field.dto;

import com.aura.catalog.field.FieldValue;

public record FieldValueResponse(
    Long id,
    Long fieldId,
    String value,
    String slug,
    int sortOrder
) {

    public static FieldValueResponse from(FieldValue fieldValue) {
        return new FieldValueResponse(fieldValue.getId(), fieldValue.getFieldId(),
            fieldValue.getValue(), fieldValue.getSlug(), fieldValue.getSortOrder());
    }
}
