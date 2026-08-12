package com.aura.catalog.color;

import com.aura.catalog.color.dto.ColorRequest;
import com.aura.catalog.color.dto.ColorResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ColorServiceTest {

    @Mock
    private ColorRepository colorRepository;

    private ColorService service;

    @BeforeEach
    void setUp() {
        service = new ColorService(colorRepository);
    }

    private Color color(long id, String name, String hex) {
        Color color = Color.of(name, hex, 0);
        color.setId(id);
        return color;
    }

    private void echoSave() {
        when(colorRepository.save(any(Color.class))).thenAnswer(i -> i.getArgument(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#ff0000", "#Ff0000", "#FF0000"})
    @DisplayName("hex is stored uppercase, so one colour is one row however it was typed")
    void hexNormalizedToUppercase(String input) {
        when(colorRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        echoSave();

        ColorResponse response = service.create(new ColorRequest("Red", input, 0, null));

        assertThat(response.hexCode()).isEqualTo("#FF0000");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed from the name and hex")
    void trimsInput() {
        when(colorRepository.findByNameIgnoreCase("Red")).thenReturn(Optional.empty());
        echoSave();

        ColorResponse response = service.create(new ColorRequest("  Red  ", "  #abcdef  ", 0, null));

        assertThat(response.name()).isEqualTo("Red");
        assertThat(response.hexCode()).isEqualTo("#ABCDEF");
    }

    @Test
    @DisplayName("a duplicate name is refused regardless of case")
    void duplicateNameRejected() {
        when(colorRepository.findByNameIgnoreCase("red")).thenReturn(Optional.of(color(1L, "Red", "#FF0000")));

        assertThatThrownBy(() -> service.create(new ColorRequest("red", "#123456", 0, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Red");

        verify(colorRepository, never()).save(any());
    }

    @Test
    @DisplayName("keeping your own name on update is not a conflict with yourself")
    void ownNameIsNotAConflict() {
        Color existing = color(1L, "Red", "#FF0000");
        when(colorRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(colorRepository.findByNameIgnoreCase("Red")).thenReturn(Optional.of(existing));
        echoSave();

        ColorResponse response = service.update(1L, new ColorRequest("Red", "#00FF00", 3, false));

        assertThat(response.hexCode()).isEqualTo("#00FF00");
        assertThat(response.sortOrder()).isEqualTo(3);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    @DisplayName("taking another colour's name on update is refused")
    void takingAnotherNameRejected() {
        when(colorRepository.findById(1L)).thenReturn(Optional.of(color(1L, "Red", "#FF0000")));
        when(colorRepository.findByNameIgnoreCase("Blue")).thenReturn(Optional.of(color(2L, "Blue", "#0000FF")));

        assertThatThrownBy(() -> service.update(1L, new ColorRequest("Blue", "#123456", 0, null)))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a colour in use by variants is not deleted, it is meant to be deactivated")
    void inUseColourNotDeleted() {
        when(colorRepository.findById(1L)).thenReturn(Optional.of(color(1L, "Red", "#FF0000")));
        when(colorRepository.isUsedByAnyVariant(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Deactivate");

        verify(colorRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an unused colour is deleted")
    void unusedColourDeleted() {
        Color unused = color(1L, "Red", "#FF0000");
        when(colorRepository.findById(1L)).thenReturn(Optional.of(unused));
        when(colorRepository.isUsedByAnyVariant(1L)).thenReturn(false);

        service.delete(1L);

        verify(colorRepository).delete(unused);
    }

    @Test
    @DisplayName("an unknown colour is a 404")
    void unknownColour() {
        when(colorRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the storefront list excludes retired colours; the admin list does not")
    void activeAndAllListsDiffer() {
        when(colorRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc())
            .thenReturn(java.util.List.of(color(1L, "Red", "#FF0000")));
        when(colorRepository.findAllByOrderBySortOrderAscNameAsc())
            .thenReturn(java.util.List.of(color(1L, "Red", "#FF0000"), color(2L, "Retired", "#000000")));

        assertThat(service.listActive()).hasSize(1);
        assertThat(service.listAll()).hasSize(2);
    }
}
