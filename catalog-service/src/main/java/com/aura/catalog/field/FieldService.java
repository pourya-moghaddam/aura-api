package com.aura.catalog.field;

import com.aura.catalog.category.CategoryService;
import com.aura.catalog.field.dto.FieldRequest;
import com.aura.catalog.field.dto.FieldResponse;
import com.aura.catalog.field.dto.FieldValueRequest;
import com.aura.catalog.field.dto.FieldValueResponse;
import com.aura.catalog.support.Slugs;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FieldService {

    private final FieldRepository fieldRepository;
    private final FieldValueRepository fieldValueRepository;
    private final CategoryService categoryService;

    /** Fields attached directly to a category — what the admin screen for that category edits. */
    @Transactional(readOnly = true)
    public List<FieldResponse> listOwnedBy(long categoryId) {
        categoryService.require(categoryId);
        List<Field> fields = fieldRepository.findByCategoryIdOrderBySortOrderAscNameAsc(categoryId);
        return toResponses(fields, categoryId);
    }

    /**
     * Every field that applies to a category, its own and its ancestors' — what the seller's product
     * form asks for once a leaf category is chosen.
     */
    @Transactional(readOnly = true)
    public List<FieldResponse> listEffectiveFor(long categoryId) {
        categoryService.require(categoryId);
        List<Field> fields = fieldRepository.findEffectiveForCategory(categoryId);
        return toResponses(fields, categoryId);
    }

    @Transactional
    public FieldResponse create(FieldRequest request) {
        categoryService.require(request.categoryId());

        String slug = resolveSlug(request.slug(), request.name(), "field");
        requireSlugFreeOnAncestryLine(slug, request.categoryId(), null);

        Field field = Field.of(request.categoryId(), request.name().trim(), slug, request.dataType());
        field.setRequired(request.isRequired());
        field.setFilterable(request.isFilterable());
        field.setSortOrder(request.sortOrder());

        return FieldResponse.from(fieldRepository.save(field), List.of(), false);
    }

    @Transactional
    public FieldResponse update(long id, FieldRequest request) {
        Field field = require(id);

        // Moving a field to another category would silently change which products carry it - every
        // product under the old category loses the attribute and every product under the new one
        // gains it, with no record of the values that were dropped. Deleting and recreating makes
        // that consequence explicit, and the in-use check below stands in the way of doing it
        // accidentally.
        if (!field.getCategoryId().equals(request.categoryId())) {
            throw new BusinessRuleException("field-category-immutable",
                "A field cannot be moved between categories. Delete it and define it on the "
                    + "other category instead.");
        }

        String slug = resolveSlug(request.slug(), request.name(), "field");
        requireSlugFreeOnAncestryLine(slug, field.getCategoryId(), id);

        // Changing the data type would strip meaning from values already recorded against products:
        // the rows survive but no longer mean what the new type says they mean.
        if (field.getDataType() != request.dataType()
            && fieldRepository.isUsedByAnyProduct(id)) {
            throw new BusinessRuleException("field-type-locked",
                "This field's type cannot be changed while products are using it.");
        }

        field.setName(request.name().trim());
        field.setSlug(slug);
        field.setDataType(request.dataType());
        field.setRequired(request.isRequired());
        field.setFilterable(request.isFilterable());
        field.setSortOrder(request.sortOrder());

        Field saved = fieldRepository.save(field);
        return FieldResponse.from(saved, valuesOf(saved.getId()), false);
    }

    @Transactional
    public void delete(long id) {
        Field field = require(id);

        if (fieldRepository.isUsedByAnyProduct(id)) {
            throw new BusinessRuleException("field-in-use",
                "This field is recorded against existing products. Clear it from them first.");
        }
        // field_values cascade at the database level, so the values go with it.
        fieldRepository.delete(field);
    }

    @Transactional(readOnly = true)
    public List<FieldValueResponse> listValues(long fieldId) {
        require(fieldId);
        return valuesOf(fieldId);
    }

    @Transactional
    public FieldValueResponse addValue(long fieldId, FieldValueRequest request) {
        Field field = require(fieldId);
        requireEnumeratedType(field);

        String slug = resolveSlug(request.slug(), request.value(), "value");
        String value = request.value().trim();

        fieldValueRepository.findByFieldIdAndValue(fieldId, value).ifPresent(existing -> {
            throw new ConflictException("field-value-taken",
                "'" + existing.getValue() + "' is already a value of this field.");
        });
        fieldValueRepository.findByFieldIdAndSlug(fieldId, slug).ifPresent(existing -> {
            throw new ConflictException("field-value-slug-taken",
                "Another value of this field already uses the slug '" + existing.getSlug() + "'.");
        });

        FieldValue saved = fieldValueRepository.save(
            FieldValue.of(fieldId, value, slug, request.sortOrder()));
        return FieldValueResponse.from(saved);
    }

    @Transactional
    public void deleteValue(long fieldId, long valueId) {
        FieldValue value = fieldValueRepository.findById(valueId)
            .filter(v -> v.getFieldId().equals(fieldId))
            .orElseThrow(() -> ResourceNotFoundException.of("Field value", valueId));

        if (fieldValueRepository.isUsedByAnyProduct(valueId)) {
            throw new BusinessRuleException("field-value-in-use",
                "This value is recorded against existing products. Clear it from them first.");
        }
        fieldValueRepository.delete(value);
    }

    @Transactional(readOnly = true)
    public Field require(long id) {
        return fieldRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Field", id));
    }

    /**
     * Loads the values for several fields in one query rather than one per field. The seller's form
     * asks for the whole effective set at once, and a category a few levels deep can easily carry a
     * dozen fields.
     */
    private List<FieldResponse> toResponses(List<Field> fields, long forCategoryId) {
        if (fields.isEmpty()) {
            return List.of();
        }

        List<Long> ids = fields.stream().map(Field::getId).toList();
        // groupingBy keeps encounter order within each group, and the query already sorts, so the
        // values arrive in display order without a second sort here.
        Map<Long, List<FieldValueResponse>> valuesByField =
            fieldValueRepository.findByFieldIdInOrderBySortOrderAscValueAsc(ids).stream()
                .map(FieldValueResponse::from)
                .collect(Collectors.groupingBy(FieldValueResponse::fieldId));

        return fields.stream()
            .map(field -> FieldResponse.from(
                field,
                valuesByField.getOrDefault(field.getId(), List.of()),
                field.getCategoryId() != forCategoryId))
            .toList();
    }

    private List<FieldValueResponse> valuesOf(long fieldId) {
        return fieldValueRepository.findByFieldIdOrderBySortOrderAscValueAsc(fieldId).stream()
            .map(FieldValueResponse::from).toList();
    }

    private void requireEnumeratedType(Field field) {
        if (!field.getDataType().usesEnumeratedValues()) {
            throw new BusinessRuleException("field-type-has-no-values",
                "A " + field.getDataType() + " field does not have a fixed list of values.");
        }
    }

    /**
     * A field's slug has to be unique across the whole ancestry line, not merely within its own
     * category — see {@link FieldRepository#findConflictingSlugOnAncestryLine}.
     */
    private void requireSlugFreeOnAncestryLine(String slug, Long categoryId, Long excludingId) {
        fieldRepository.findConflictingSlugOnAncestryLine(slug, categoryId)
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("field-slug-taken",
                    "The slug '" + slug + "' is already used by the field '" + existing.getName()
                        + "' on a category in the same branch, so a product would end up with the "
                        + "attribute twice.");
            });
    }

    private String resolveSlug(String supplied, String source, String what) {
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }

        String derived = Slugs.deriveOrNull(source);
        if (derived == null) {
            throw new BusinessRuleException("slug-required",
                "A slug could not be derived from this " + what + "'s name, which happens when the "
                    + "name has no Latin characters. Please supply one.");
        }
        return derived;
    }
}
