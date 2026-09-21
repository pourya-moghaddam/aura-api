package com.aura.catalog.size;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryService;
import com.aura.catalog.size.dto.SizeRequest;
import com.aura.catalog.size.dto.SizeResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SizeServiceTest {

    @Mock
    private SizeRepository sizeRepository;

    @Mock
    private CategoryService categoryService;

    private SizeService service;

    @BeforeEach
    void setUp() {
        service = new SizeService(sizeRepository, categoryService);
    }

    private Size size(long id, String name, Long categoryId) {
        Size size = Size.of(name, categoryId, 0);
        size.setId(id);
        return size;
    }

    private void echoSave() {
        when(sizeRepository.save(any(Size.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a global size and a category-scoped size may share a name")
    void sameNameInDifferentScopesAllowed() {
        // Mirrors UNIQUE NULLS NOT DISTINCT (name, category_id): "L" means something different
        // under Footwear than it does globally, so both must be able to exist.
        when(sizeRepository.findByNameAndScope("L", null)).thenReturn(Optional.empty());
        echoSave();

        service.create(new SizeRequest("L", null, 0, null));

        when(categoryService.require(5L)).thenReturn(new Category());
        when(sizeRepository.findByNameAndScope("L", 5L)).thenReturn(Optional.empty());

        SizeResponse scoped = service.create(new SizeRequest("L", 5L, 0, null));

        assertThat(scoped.categoryId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a duplicate name within the same scope is refused")
    void duplicateWithinScopeRejected() {
        when(sizeRepository.findByNameAndScope("L", null)).thenReturn(Optional.of(size(1L, "L", null)));

        assertThatThrownBy(() -> service.create(new SizeRequest("L", null, 0, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists in this scope");

        verify(sizeRepository, never()).save(any());
    }

    @Test
    @DisplayName("scoping to an unknown category is a 404, not a silently global size")
    void unknownCategoryRejected() {
        when(categoryService.require(99L)).thenThrow(ResourceNotFoundException.of("Category", 99L));

        assertThatThrownBy(() -> service.create(new SizeRequest("L", 99L, 0, null)))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(sizeRepository, never()).save(any());
    }

    @Test
    @DisplayName("the name is trimmed before it is stored or compared")
    void nameTrimmed() {
        when(sizeRepository.findByNameAndScope("L", null)).thenReturn(Optional.empty());
        echoSave();

        assertThat(service.create(new SizeRequest("  L  ", null, 0, null)).name()).isEqualTo("L");
    }

    @Test
    @DisplayName("keeping your own name on update is not a conflict with yourself")
    void ownNameIsNotAConflict() {
        Size existing = size(1L, "L", null);
        when(sizeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(sizeRepository.findByNameAndScope("L", null)).thenReturn(Optional.of(existing));
        echoSave();

        SizeResponse response = service.update(1L, new SizeRequest("L", null, 4, false));

        assertThat(response.sortOrder()).isEqualTo(4);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    @DisplayName("listing for a category validates the category first")
    void listForCategoryValidatesCategory() {
        // Otherwise an unknown category returns an empty list, which is indistinguishable from a
        // real category that simply has no sizes yet.
        when(categoryService.require(99L)).thenThrow(ResourceNotFoundException.of("Category", 99L));

        assertThatThrownBy(() -> service.listForCategory(99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(sizeRepository, never()).findAvailableForCategory(any());
    }

    @Test
    @DisplayName("listing for a category returns what the repository resolved through the ancestry")
    void listForCategoryDelegatesToAncestryQuery() {
        when(categoryService.require(5L)).thenReturn(new Category());
        when(sizeRepository.findAvailableForCategory(5L))
            .thenReturn(List.of(size(1L, "L", null), size(2L, "42", 5L)));

        assertThat(service.listForCategory(5L)).extracting(SizeResponse::name)
            .containsExactly("L", "42");
    }

    @Test
    @DisplayName("a size in use by variants is not deleted, it is meant to be deactivated")
    void inUseSizeNotDeleted() {
        when(sizeRepository.findById(1L)).thenReturn(Optional.of(size(1L, "L", null)));
        when(sizeRepository.isUsedByAnyVariant(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Deactivate");

        verify(sizeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an unused size is deleted")
    void unusedSizeDeleted() {
        Size unused = size(1L, "L", null);
        when(sizeRepository.findById(1L)).thenReturn(Optional.of(unused));
        when(sizeRepository.isUsedByAnyVariant(1L)).thenReturn(false);

        service.delete(1L);

        verify(sizeRepository).delete(unused);
    }

    @Test
    @DisplayName("an unknown size is a 404")
    void unknownSize() {
        when(sizeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
