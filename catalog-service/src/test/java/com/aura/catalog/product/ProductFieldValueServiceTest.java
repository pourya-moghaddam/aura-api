package com.aura.catalog.product;

import com.aura.catalog.field.Field;
import com.aura.catalog.field.FieldDataType;
import com.aura.catalog.field.FieldRepository;
import com.aura.catalog.field.FieldValue;
import com.aura.catalog.field.FieldValueRepository;
import com.aura.catalog.product.dto.ProductRequest.ProductFieldValueRequest;
import com.aura.common.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Field-value validation — a named priority target in the plan (§11).
 *
 * <p>The foreign keys stop a value being invented, but not the three ways a perfectly valid pair
 * of rows can still describe nonsense: a field from another category, a value belonging to a
 * different field, or a required field left unanswered. None of those fail at the database, and
 * none are visible until the storefront's facets come out wrong.
 */
@ExtendWith(MockitoExtension.class)
class ProductFieldValueServiceTest {

    private static final long PRODUCT_ID = 100L;
    private static final long CATEGORY_ID = 5L;

    @Mock
    private ProductFieldValueRepository productFieldValueRepository;

    @Mock
    private FieldRepository fieldRepository;

    @Mock
    private FieldValueRepository fieldValueRepository;

    private ProductFieldValueService service;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductFieldValueService(
            productFieldValueRepository, fieldRepository, fieldValueRepository);
        product = Product.draft(1L, CATEGORY_ID, "Shirt", "shirt", null);
        product.setId(PRODUCT_ID);
    }

    private Field field(long id, FieldDataType type, boolean required) {
        Field f = Field.of(CATEGORY_ID, "Field" + id, "field" + id, type);
        f.setId(id);
        f.setRequired(required);
        return f;
    }

    private FieldValue value(long id, long fieldId) {
        FieldValue v = FieldValue.of(fieldId, "Value" + id, "value" + id, 0);
        v.setId(id);
        return v;
    }

    private void effective(Field... fields) {
        when(fieldRepository.findEffectiveForCategory(CATEGORY_ID)).thenReturn(List.of(fields));
    }

    private void valuesExist(FieldValue... values) {
        when(fieldValueRepository.findAllById(any())).thenReturn(List.of(values));
    }

    @Test
    @DisplayName("a valid answer is stored and the JSONB is rebuilt from it")
    void validAnswerStored() {
        effective(field(1L, FieldDataType.SELECT, false));
        valuesExist(value(10L, 1L));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID))
            .thenReturn(List.of(slugPair("material", "cotton")));

        service.replace(product, List.of(new ProductFieldValueRequest(1L, List.of(10L))));

        verify(productFieldValueRepository).saveAll(argThat(
            (Iterable<ProductFieldValue> rows) -> rows.iterator().hasNext()));
        assertThat(product.getAttributes()).containsEntry("material", List.of("cotton"));
    }

    @Test
    @DisplayName("a field from another category is refused")
    void fieldOutsideCategoryRefused() {
        // The foreign key would accept this happily - the field row exists, it just has nothing to
        // do with the category this product is filed under.
        effective(field(1L, FieldDataType.SELECT, false));

        assertThatThrownBy(() -> service.replace(product,
            List.of(new ProductFieldValueRequest(99L, List.of(10L)))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("does not apply to this product's category");

        verify(productFieldValueRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a value belonging to a different field is refused")
    void mismatchedValueRefused() {
        // Both rows exist and both foreign keys are satisfied, but pairing "Cotton" with "Sleeve
        // length" is meaningless. This is the case the database structurally cannot catch.
        effective(field(1L, FieldDataType.SELECT, false), field(2L, FieldDataType.SELECT, false));
        valuesExist(value(20L, 2L));

        assertThatThrownBy(() -> service.replace(product,
            List.of(new ProductFieldValueRequest(1L, List.of(20L)))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("is not a value of the field");
    }

    @Test
    @DisplayName("a value that does not exist at all is refused")
    void unknownValueRefused() {
        effective(field(1L, FieldDataType.SELECT, false));
        when(fieldValueRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.replace(product,
            List.of(new ProductFieldValueRequest(1L, List.of(999L)))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("an unanswered required field is refused")
    void requiredFieldMustBeAnswered() {
        effective(field(1L, FieldDataType.SELECT, true));

        assertThatThrownBy(() -> service.replace(product, List.of()))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("is required for this category");
    }

    @Test
    @DisplayName("an optional field may be left unanswered")
    void optionalFieldMayBeOmitted() {
        effective(field(1L, FieldDataType.SELECT, false));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of());

        assertThatCode(() -> service.replace(product, List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a SELECT field accepts only one value")
    void selectRejectsMultipleValues() {
        effective(field(1L, FieldDataType.SELECT, false));
        valuesExist(value(10L, 1L), value(11L, 1L));

        assertThatThrownBy(() -> service.replace(product,
            List.of(new ProductFieldValueRequest(1L, List.of(10L, 11L)))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("accepts only one value");
    }

    @Test
    @DisplayName("a MULTI_SELECT field accepts several")
    void multiSelectAcceptsMultipleValues() {
        effective(field(1L, FieldDataType.MULTI_SELECT, false));
        valuesExist(value(10L, 1L), value(11L, 1L));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID))
            .thenReturn(List.of(slugPair("tags", "a"), slugPair("tags", "b")));

        service.replace(product, List.of(new ProductFieldValueRequest(1L, List.of(10L, 11L))));

        assertThat(product.getAttributes()).containsEntry("tags", List.of("a", "b"));
    }

    @Test
    @DisplayName("a field type with no value list cannot be given values")
    void nonEnumeratedTypeRefusesValues() {
        effective(field(1L, FieldDataType.NUMBER, false));
        valuesExist(value(10L, 1L));

        assertThatThrownBy(() -> service.replace(product,
            List.of(new ProductFieldValueRequest(1L, List.of(10L)))))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("does not take values from a list");
    }

    @Test
    @DisplayName("a required field of a type with no value list is not enforced, since it cannot be met")
    void unsatisfiableRequiredFieldIsNotEnforced() {
        effective(field(1L, FieldDataType.NUMBER, true));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of());

        assertThatCode(() -> service.replace(product, List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the same field submitted twice is merged rather than rejected")
    void duplicateFieldEntriesMerged() {
        effective(field(1L, FieldDataType.MULTI_SELECT, false));
        valuesExist(value(10L, 1L), value(11L, 1L));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of());

        assertThatCode(() -> service.replace(product, List.of(
            new ProductFieldValueRequest(1L, List.of(10L)),
            new ProductFieldValueRequest(1L, List.of(11L)))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the same value submitted twice is stored once, not as a duplicate primary key")
    void duplicateValueDeduplicated() {
        effective(field(1L, FieldDataType.MULTI_SELECT, false));
        valuesExist(value(10L, 1L));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of());

        service.replace(product, List.of(new ProductFieldValueRequest(1L, List.of(10L, 10L))));

        verify(productFieldValueRepository).saveAll(argThat(
            (Iterable<ProductFieldValue> rows) -> {
                int count = 0;
                for (ProductFieldValue ignored : rows) {
                    count++;
                }
                return count == 1;
            }));
    }

    @Test
    @DisplayName("existing values are cleared first, so a re-categorised product keeps no stale answers")
    void replaceClearsBeforeWriting() {
        effective(field(1L, FieldDataType.SELECT, false));
        valuesExist(value(10L, 1L));
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of());

        service.replace(product, List.of(new ProductFieldValueRequest(1L, List.of(10L))));

        var inOrder = inOrder(productFieldValueRepository);
        inOrder.verify(productFieldValueRepository).deleteByProductId(PRODUCT_ID);
        inOrder.verify(productFieldValueRepository).saveAll(any());
    }

    @Test
    @DisplayName("the JSONB groups several values under one field slug")
    void attributesGroupByFieldSlug() {
        when(productFieldValueRepository.findSlugPairs(PRODUCT_ID)).thenReturn(List.of(
            slugPair("material", "cotton"),
            slugPair("material", "wool"),
            slugPair("collar", "button-down")));

        assertThat(service.buildAttributes(PRODUCT_ID))
            .containsEntry("material", List.of("cotton", "wool"))
            .containsEntry("collar", List.of("button-down"));
    }

    private ProductFieldValueRepository.SlugPair slugPair(String fieldSlug, String valueSlug) {
        return new ProductFieldValueRepository.SlugPair() {
            @Override
            public String getFieldSlug() {
                return fieldSlug;
            }

            @Override
            public String getValueSlug() {
                return valueSlug;
            }
        };
    }
}
