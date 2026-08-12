package com.aura.catalog.field;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryService;
import com.aura.catalog.field.dto.FieldRequest;
import com.aura.catalog.field.dto.FieldResponse;
import com.aura.catalog.field.dto.FieldValueRequest;
import com.aura.catalog.field.dto.FieldValueResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Field-value validation is a named priority test target in the development plan (§11): without it
 * a seller can attach a value that is not in the admin's option list, and the resulting facet
 * buckets are wrong in a way nobody notices until the storefront sidebar is built.
 */
@ExtendWith(MockitoExtension.class)
class FieldServiceTest {

    private static final long CATEGORY_ID = 5L;

    @Mock
    private FieldRepository fieldRepository;

    @Mock
    private FieldValueRepository fieldValueRepository;

    @Mock
    private CategoryService categoryService;

    private FieldService service;

    @BeforeEach
    void setUp() {
        service = new FieldService(fieldRepository, fieldValueRepository, categoryService);
    }

    private Field field(long id, Long categoryId, String slug, FieldDataType type) {
        Field f = Field.of(categoryId, "Name" + id, slug, type);
        f.setId(id);
        return f;
    }

    private FieldValue value(long id, long fieldId, String v, String slug) {
        FieldValue fv = FieldValue.of(fieldId, v, slug, 0);
        fv.setId(id);
        return fv;
    }

    private FieldRequest request(String name, String slug) {
        return new FieldRequest(CATEGORY_ID, name, slug, FieldDataType.SELECT, false, true, 0);
    }

    private void echoSaveField() {
        when(fieldRepository.save(any(Field.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    class SlugResolution {

        @Test
        @DisplayName("a slug is derived from the name when none is supplied")
        void derivedFromName() {
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findConflictingSlugOnAncestryLine("sleeve-length", CATEGORY_ID))
                .thenReturn(Optional.empty());
            echoSaveField();

            FieldResponse response = service.create(request("Sleeve Length", null));

            assertThat(response.slug()).isEqualTo("sleeve-length");
        }

        @Test
        @DisplayName("an explicit slug wins over the derived one")
        void explicitSlugWins() {
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findConflictingSlugOnAncestryLine("custom", CATEGORY_ID))
                .thenReturn(Optional.empty());
            echoSaveField();

            assertThat(service.create(request("Sleeve Length", "custom")).slug()).isEqualTo("custom");
        }

        @Test
        @DisplayName("a Persian name with no slug asks for one rather than inventing an identifier")
        void persianNameNeedsExplicitSlug() {
            // A generated fallback like "field-17" would end up in a public filter URL permanently,
            // so refusing and asking is the honest outcome.
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());

            assertThatThrownBy(() -> service.create(request("جنس پارچه", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("supply one");

            verify(fieldRepository, never()).save(any());
        }
    }

    @Nested
    class AncestryLineUniqueness {

        @Test
        @DisplayName("a slug already used anywhere on the same branch is refused")
        void conflictOnBranchRejected() {
            // The per-category UNIQUE constraint cannot catch this: "material" on Clothing and on
            // Clothing > Men are different rows, but a Men product inherits both and ends up with
            // the attribute twice.
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findConflictingSlugOnAncestryLine("material", CATEGORY_ID))
                .thenReturn(Optional.of(field(1L, 2L, "material", FieldDataType.SELECT)));

            assertThatThrownBy(() -> service.create(request("Material", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("same branch");

            verify(fieldRepository, never()).save(any());
        }

        @Test
        @DisplayName("a field does not conflict with itself when updated")
        void selfIsNotAConflict() {
            Field existing = field(1L, CATEGORY_ID, "material", FieldDataType.SELECT);
            when(fieldRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(fieldRepository.findConflictingSlugOnAncestryLine("material", CATEGORY_ID))
                .thenReturn(Optional.of(existing));
            when(fieldValueRepository.findByFieldIdOrderBySortOrderAscValueAsc(1L)).thenReturn(List.of());
            echoSaveField();

            FieldResponse response = service.update(1L, request("Material", "material"));

            assertThat(response.slug()).isEqualTo("material");
        }
    }

    @Nested
    class Updates {

        @Test
        @DisplayName("a field cannot be moved to another category")
        void categoryIsImmutable() {
            // Moving it would silently strip the attribute from every product under the old
            // category and add it to every product under the new one, discarding recorded values.
            Field existing = field(1L, CATEGORY_ID, "material", FieldDataType.SELECT);
            when(fieldRepository.findById(1L)).thenReturn(Optional.of(existing));

            FieldRequest movedElsewhere =
                new FieldRequest(99L, "Material", "material", FieldDataType.SELECT, false, true, 0);

            assertThatThrownBy(() -> service.update(1L, movedElsewhere))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be moved");
        }

        @Test
        @DisplayName("the data type is locked once products carry values for the field")
        void typeLockedWhileInUse() {
            Field existing = field(1L, CATEGORY_ID, "material", FieldDataType.SELECT);
            when(fieldRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(fieldRepository.findConflictingSlugOnAncestryLine(anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(fieldRepository.isUsedByAnyProduct(1L)).thenReturn(true);

            FieldRequest retyped = new FieldRequest(
                CATEGORY_ID, "Material", "material", FieldDataType.NUMBER, false, true, 0);

            assertThatThrownBy(() -> service.update(1L, retyped))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be changed");
        }

        @Test
        @DisplayName("the data type may change while nothing uses the field")
        void typeChangeableWhileUnused() {
            Field existing = field(1L, CATEGORY_ID, "material", FieldDataType.SELECT);
            when(fieldRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(fieldRepository.findConflictingSlugOnAncestryLine(anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(fieldRepository.isUsedByAnyProduct(1L)).thenReturn(false);
            when(fieldValueRepository.findByFieldIdOrderBySortOrderAscValueAsc(1L)).thenReturn(List.of());
            echoSaveField();

            FieldRequest retyped = new FieldRequest(
                CATEGORY_ID, "Material", "material", FieldDataType.NUMBER, true, false, 3);

            FieldResponse response = service.update(1L, retyped);

            assertThat(response.dataType()).isEqualTo(FieldDataType.NUMBER);
            assertThat(response.isRequired()).isTrue();
            assertThat(response.isFilterable()).isFalse();
        }
    }

    @Nested
    class Values {

        @Test
        @DisplayName("a value's slug is derived from the value when none is supplied")
        void slugDerivedFromValue() {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "material", FieldDataType.SELECT)));
            when(fieldValueRepository.findByFieldIdAndValue(1L, "Cotton")).thenReturn(Optional.empty());
            when(fieldValueRepository.findByFieldIdAndSlug(1L, "cotton")).thenReturn(Optional.empty());
            when(fieldValueRepository.save(any(FieldValue.class))).thenAnswer(i -> i.getArgument(0));

            FieldValueResponse response = service.addValue(1L, new FieldValueRequest("Cotton", null, 0));

            assertThat(response.slug()).isEqualTo("cotton");
        }

        @ParameterizedTest
        @EnumSource(value = FieldDataType.class, names = {"NUMBER", "BOOLEAN"})
        @DisplayName("data types without an option list refuse enumerated values")
        void nonEnumeratedTypesRefuseValues(FieldDataType type) {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "weight", type)));

            assertThatThrownBy(() -> service.addValue(1L, new FieldValueRequest("120", null, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not have a fixed list");

            verify(fieldValueRepository, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = FieldDataType.class, names = {"SELECT", "MULTI_SELECT"})
        @DisplayName("data types with an option list accept values")
        void enumeratedTypesAcceptValues(FieldDataType type) {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "material", type)));
            when(fieldValueRepository.findByFieldIdAndValue(anyLong(), anyString())).thenReturn(Optional.empty());
            when(fieldValueRepository.findByFieldIdAndSlug(anyLong(), anyString())).thenReturn(Optional.empty());
            when(fieldValueRepository.save(any(FieldValue.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(service.addValue(1L, new FieldValueRequest("Cotton", null, 0))).isNotNull();
        }

        @Test
        @DisplayName("a duplicate value is refused regardless of case")
        void duplicateValueRejected() {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "material", FieldDataType.SELECT)));
            when(fieldValueRepository.findByFieldIdAndValue(1L, "cotton"))
                .thenReturn(Optional.of(value(2L, 1L, "Cotton", "cotton")));

            assertThatThrownBy(() -> service.addValue(1L, new FieldValueRequest("cotton", null, 0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a value");
        }

        @Test
        @DisplayName("two different values may not collide on one slug")
        void duplicateSlugRejected() {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "material", FieldDataType.SELECT)));
            when(fieldValueRepository.findByFieldIdAndValue(anyLong(), anyString())).thenReturn(Optional.empty());
            when(fieldValueRepository.findByFieldIdAndSlug(1L, "cotton"))
                .thenReturn(Optional.of(value(2L, 1L, "Cotton", "cotton")));

            assertThatThrownBy(() -> service.addValue(1L, new FieldValueRequest("Coton", "cotton", 0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("slug");
        }

        @Test
        @DisplayName("a value in use by products is not deleted")
        void inUseValueNotDeleted() {
            when(fieldValueRepository.findById(2L)).thenReturn(Optional.of(value(2L, 1L, "Cotton", "cotton")));
            when(fieldValueRepository.isUsedByAnyProduct(2L)).thenReturn(true);

            assertThatThrownBy(() -> service.deleteValue(1L, 2L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("recorded against existing products");

            verify(fieldValueRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a value belonging to a different field is a 404, not someone else's deletion")
        void valueFromAnotherFieldIsNotFound() {
            when(fieldValueRepository.findById(2L)).thenReturn(Optional.of(value(2L, 99L, "Cotton", "cotton")));

            assertThatThrownBy(() -> service.deleteValue(1L, 2L))
                .isInstanceOf(ResourceNotFoundException.class);

            verify(fieldValueRepository, never()).delete(any());
        }
    }

    @Nested
    class EffectiveSet {

        @Test
        @DisplayName("inherited fields are marked so the panel knows not to edit them here")
        void inheritedFlagSetForAncestorOwnedFields() {
            Field own = field(1L, CATEGORY_ID, "collar", FieldDataType.SELECT);
            Field inherited = field(2L, 99L, "material", FieldDataType.SELECT);

            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findEffectiveForCategory(CATEGORY_ID))
                .thenReturn(List.of(inherited, own));
            when(fieldValueRepository.findByFieldIdInOrderBySortOrderAscValueAsc(List.of(2L, 1L)))
                .thenReturn(List.of(value(10L, 2L, "Cotton", "cotton")));

            List<FieldResponse> effective = service.listEffectiveFor(CATEGORY_ID);

            assertThat(effective).extracting(FieldResponse::slug).containsExactly("material", "collar");
            assertThat(effective.get(0).inherited()).isTrue();
            assertThat(effective.get(0).values()).extracting(FieldValueResponse::slug).containsExactly("cotton");
            assertThat(effective.get(1).inherited()).isFalse();
            assertThat(effective.get(1).values()).isEmpty();
        }

        @Test
        @DisplayName("the admin view of a category marks nothing as inherited")
        void ownedFieldsAreNeverInherited() {
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findByCategoryIdOrderBySortOrderAscNameAsc(CATEGORY_ID))
                .thenReturn(List.of(field(1L, CATEGORY_ID, "collar", FieldDataType.SELECT)));
            when(fieldValueRepository.findByFieldIdInOrderBySortOrderAscValueAsc(List.of(1L)))
                .thenReturn(List.of());

            assertThat(service.listOwnedBy(CATEGORY_ID)).allMatch(f -> !f.inherited());
        }

        @Test
        @DisplayName("an empty field set skips the values query entirely")
        void emptySetShortCircuits() {
            when(categoryService.require(CATEGORY_ID)).thenReturn(new Category());
            when(fieldRepository.findEffectiveForCategory(CATEGORY_ID)).thenReturn(List.of());

            assertThat(service.listEffectiveFor(CATEGORY_ID)).isEmpty();
            verify(fieldValueRepository, never()).findByFieldIdInOrderBySortOrderAscValueAsc(any());
        }
    }

    @Nested
    class Deletion {

        @Test
        @DisplayName("a field in use by products is not deleted")
        void inUseFieldNotDeleted() {
            when(fieldRepository.findById(1L))
                .thenReturn(Optional.of(field(1L, CATEGORY_ID, "material", FieldDataType.SELECT)));
            when(fieldRepository.isUsedByAnyProduct(1L)).thenReturn(true);

            assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("recorded against existing products");

            verify(fieldRepository, never()).delete(any());
        }

        @Test
        @DisplayName("an unused field is deleted and its values cascade")
        void unusedFieldDeleted() {
            Field unused = field(1L, CATEGORY_ID, "material", FieldDataType.SELECT);
            when(fieldRepository.findById(1L)).thenReturn(Optional.of(unused));
            when(fieldRepository.isUsedByAnyProduct(1L)).thenReturn(false);

            service.delete(1L);

            verify(fieldRepository).delete(unused);
        }

        @Test
        @DisplayName("an unknown field is a 404")
        void unknownField() {
            when(fieldRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.require(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
