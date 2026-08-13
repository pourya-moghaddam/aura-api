package com.aura.order.delivery;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.delivery.dto.DeliveryMethodRequest;
import com.aura.order.delivery.dto.DeliveryMethodResponse;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryMethodServiceTest {

    @Mock
    private DeliveryMethodRepository deliveryMethodRepository;

    private DeliveryMethodService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryMethodService(deliveryMethodRepository);
    }

    private DeliveryMethod method(long id, String name, long fee, boolean active) {
        DeliveryMethod m = DeliveryMethod.of(name, "desc", fee, 0);
        m.setId(id);
        m.setActive(active);
        return m;
    }

    private DeliveryMethodRequest request(String name, Long fee) {
        return new DeliveryMethodRequest(name, "Arrives in 3 days", fee, true, 0);
    }

    private void echoSave() {
        when(deliveryMethodRepository.save(any(DeliveryMethod.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a method is created with its fee in Rial")
    void creates() {
        when(deliveryMethodRepository.findByNameIgnoreCase("Post")).thenReturn(Optional.empty());
        echoSave();

        DeliveryMethodResponse response = service.create(request("Post", 250_000L));

        assertThat(response.name()).isEqualTo("Post");
        assertThat(response.fee()).isEqualTo(250_000L);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("free delivery is allowed — zero is a real offer, not a missing value")
    void zeroFeeAllowed() {
        when(deliveryMethodRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        echoSave();

        assertThat(service.create(request("Free over 5m", 0L)).fee()).isZero();
    }

    @Test
    @DisplayName("a duplicate name is refused regardless of case")
    void duplicateNameRefused() {
        when(deliveryMethodRepository.findByNameIgnoreCase("post"))
            .thenReturn(Optional.of(method(1L, "Post", 250_000L, true)));

        assertThatThrownBy(() -> service.create(request("post", 100L)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Post");

        verify(deliveryMethodRepository, never()).save(any());
    }

    @Test
    @DisplayName("keeping your own name on update is not a conflict with yourself")
    void ownNameIsNotAConflict() {
        DeliveryMethod existing = method(1L, "Post", 250_000L, true);
        when(deliveryMethodRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(deliveryMethodRepository.findByNameIgnoreCase("Post")).thenReturn(Optional.of(existing));
        echoSave();

        assertThat(service.update(1L, request("Post", 300_000L)).fee()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("the storefront list excludes retired methods; the admin list does not")
    void activeAndAllListsDiffer() {
        when(deliveryMethodRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(method(1L, "Post", 250_000L, true)));
        when(deliveryMethodRepository.findAllByOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(method(1L, "Post", 250_000L, true),
                method(2L, "Retired", 100L, false)));

        assertThat(service.listActive()).hasSize(1);
        assertThat(service.listAll()).hasSize(2);
    }

    @Test
    @DisplayName("deleting retires rather than removes, so past orders still resolve")
    void deleteDeactivates() {
        // The foreign key is ON DELETE RESTRICT, so an actual delete would fail on the first order
        // that used the method - or succeed for an unused one and behave differently later.
        DeliveryMethod existing = method(1L, "Post", 250_000L, true);
        when(deliveryMethodRepository.findById(1L)).thenReturn(Optional.of(existing));
        echoSave();

        assertThat(service.deactivate(1L).isActive()).isFalse();
        verify(deliveryMethodRepository, never()).delete(any());
    }

    @Test
    @DisplayName("checkout accepts an active method")
    void activeMethodIsSelectable() {
        DeliveryMethod active = method(1L, "Post", 250_000L, true);
        when(deliveryMethodRepository.findById(1L)).thenReturn(Optional.of(active));

        assertThat(service.requireSelectable(1L)).isSameAs(active);
    }

    @Test
    @DisplayName("checkout refuses a retired method even though it is never offered")
    void retiredMethodIsNotSelectable() {
        // The id arrives in a request body. A checkout replayed from a stale page, or edited by
        // hand, would otherwise ship at a price the shop has withdrawn.
        when(deliveryMethodRepository.findById(1L))
            .thenReturn(Optional.of(method(1L, "Post", 250_000L, false)));

        assertThatThrownBy(() -> service.requireSelectable(1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("no longer available");
    }

    @Test
    @DisplayName("an unknown method is a 404")
    void unknownMethod() {
        when(deliveryMethodRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireSelectable(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a blank description is stored as null rather than an empty string")
    void blankDescriptionBecomesNull() {
        when(deliveryMethodRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        echoSave();

        DeliveryMethodResponse response = service.create(
            new DeliveryMethodRequest("Post", "   ", 100L, true, 0));

        assertThat(response.description()).isNull();
    }

    @Test
    @DisplayName("names are trimmed before being stored or compared")
    void namesAreTrimmed() {
        when(deliveryMethodRepository.findByNameIgnoreCase("Post")).thenReturn(Optional.empty());
        echoSave();

        assertThat(service.create(request("  Post  ", 100L)).name()).isEqualTo("Post");
    }
}
