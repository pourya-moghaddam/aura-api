package com.aura.catalog.product;

import com.aura.catalog.field.Field;
import com.aura.catalog.field.FieldRepository;
import com.aura.catalog.field.FieldValue;
import com.aura.catalog.field.FieldValueRepository;
import com.aura.catalog.product.dto.ProductRequest;
import com.aura.common.web.error.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates and stores a seller's field values, then rebuilds the denormalised JSONB from them.
 *
 * <p>This is the integrity half of requirement 5. The foreign keys on
 * {@code product_field_values} make an invented value impossible to store, but they cannot express
 * the three rules that actually matter to a seller: the field has to be one their category
 * inherits, the value has to belong to that field, and a required field has to be answered. All
 * three fail silently if left to the database — the first two would store a perfectly valid row
 * describing something nonsensical.
 */
@Service
@RequiredArgsConstructor
public class ProductFieldValueService {

    private final ProductFieldValueRepository productFieldValueRepository;
    private final FieldRepository fieldRepository;
    private final FieldValueRepository fieldValueRepository;

    /**
     * Replaces a product's field values wholesale.
     *
     * <p>Replace rather than merge: the request carries the seller's complete answer, and a merge
     * would leave values from a previous category silently attached after a re-categorisation.
     */
    @Transactional
    public void replace(Product product, List<ProductRequest.ProductFieldValueRequest> submitted) {
        List<Field> effective = fieldRepository.findEffectiveForCategory(product.getCategoryId());
        Map<Long, Field> effectiveById = effective.stream()
            .collect(Collectors.toMap(Field::getId, Function.identity()));

        Map<Long, List<Long>> chosen = collapse(submitted);

        validateFieldsAreInScope(chosen.keySet(), effectiveById);
        validateValuesBelongToTheirFields(chosen, effectiveById);
        validateCardinality(chosen, effectiveById);
        validateRequiredFieldsAnswered(effective, chosen);

        productFieldValueRepository.deleteByProductId(product.getId());

        List<ProductFieldValue> rows = new ArrayList<>();
        chosen.forEach((fieldId, valueIds) -> valueIds.forEach(valueId ->
            rows.add(ProductFieldValue.of(product.getId(), fieldId, valueId))));
        productFieldValueRepository.saveAll(rows);

        // Flushed by saveAll above, so the rebuild below reads what was just written.
        product.setAttributes(buildAttributes(product.getId()));
    }

    /**
     * Rebuilds {@code products.attributes} from the normalised rows.
     *
     * <p>Always derived, never edited. The moment anything writes to the JSONB directly it stops
     * being a projection of the truth and becomes a second, unconstrained copy of it.
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> buildAttributes(long productId) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (ProductFieldValueRepository.SlugPair pair : productFieldValueRepository.findSlugPairs(productId)) {
            attributes.computeIfAbsent(pair.getFieldSlug(), key -> new ArrayList<>())
                .add(pair.getValueSlug());
        }
        return attributes;
    }

    /** Tolerates a client sending the same field twice rather than rejecting on a technicality. */
    private Map<Long, List<Long>> collapse(List<ProductRequest.ProductFieldValueRequest> submitted) {
        Map<Long, List<Long>> chosen = new LinkedHashMap<>();
        for (ProductRequest.ProductFieldValueRequest entry : submitted) {
            List<Long> target = chosen.computeIfAbsent(entry.fieldId(), key -> new ArrayList<>());
            for (Long valueId : entry.valueIds()) {
                if (!target.contains(valueId)) {
                    target.add(valueId);
                }
            }
        }
        return chosen;
    }

    private void validateFieldsAreInScope(Set<Long> submittedFieldIds, Map<Long, Field> effectiveById) {
        for (Long fieldId : submittedFieldIds) {
            if (!effectiveById.containsKey(fieldId)) {
                throw new BusinessRuleException("field-not-in-category",
                    "Field " + fieldId + " does not apply to this product's category.");
            }
        }
    }

    private void validateValuesBelongToTheirFields(Map<Long, List<Long>> chosen,
                                                   Map<Long, Field> effectiveById) {
        List<Long> allValueIds = chosen.values().stream().flatMap(List::stream).toList();
        if (allValueIds.isEmpty()) {
            return;
        }

        Map<Long, FieldValue> valuesById = fieldValueRepository.findAllById(allValueIds).stream()
            .collect(Collectors.toMap(FieldValue::getId, Function.identity()));

        chosen.forEach((fieldId, valueIds) -> {
            for (Long valueId : valueIds) {
                FieldValue value = valuesById.get(valueId);
                if (value == null) {
                    throw new BusinessRuleException("field-value-unknown",
                        "Value " + valueId + " does not exist.");
                }
                // The rule the foreign key cannot express: both rows exist, but they belong to
                // different fields, so the pairing is meaningless.
                if (!value.getFieldId().equals(fieldId)) {
                    throw new BusinessRuleException("field-value-mismatched",
                        "'" + value.getValue() + "' is not a value of the field '"
                            + effectiveById.get(fieldId).getName() + "'.");
                }
            }
        });
    }

    private void validateCardinality(Map<Long, List<Long>> chosen, Map<Long, Field> effectiveById) {
        chosen.forEach((fieldId, valueIds) -> {
            Field field = effectiveById.get(fieldId);

            if (!field.getDataType().usesEnumeratedValues()) {
                throw new BusinessRuleException("field-type-has-no-values",
                    "The field '" + field.getName() + "' does not take values from a list.");
            }
            if (valueIds.size() > 1 && !field.getDataType().allowsMultipleValues()) {
                throw new BusinessRuleException("field-single-value-only",
                    "The field '" + field.getName() + "' accepts only one value.");
            }
        });
    }

    private void validateRequiredFieldsAnswered(List<Field> effective, Map<Long, List<Long>> chosen) {
        for (Field field : effective) {
            // A required field of a type that has no value list would be unsatisfiable, so it
            // cannot be enforced here. Nothing produces one today; the guard keeps it that way.
            if (field.isRequired() && field.getDataType().usesEnumeratedValues()
                && chosen.getOrDefault(field.getId(), List.of()).isEmpty()) {
                throw new BusinessRuleException("field-required",
                    "The field '" + field.getName() + "' is required for this category.");
            }
        }
    }
}
