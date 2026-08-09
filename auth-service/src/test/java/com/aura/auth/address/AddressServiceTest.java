package com.aura.auth.address;

import com.aura.auth.address.dto.AddressRequest;
import com.aura.auth.address.dto.AddressResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Mock
    private AddressRepository addressRepository;

    private AddressService service;

    @BeforeEach
    void setUp() {
        service = new AddressService(addressRepository);
    }

    private AddressRequest request(boolean isDefault) {
        return new AddressRequest(
            "Home", "Ali", "Rezaei", "09121234567",
            "Tehran", "Tehran", "Valiasr St, No 1", null, "1234567890", isDefault);
    }

    private Address address(long id, long userId, boolean isDefault) {
        Address address = new Address();
        address.setId(id);
        address.setUserId(userId);
        address.setDefault(isDefault);
        address.setPhone("+989121234567");
        return address;
    }

    private void echoSave() {
        when(addressRepository.save(any(Address.class))).thenAnswer(i -> i.getArgument(0));
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void theFirstAddressBecomesTheDefaultEvenWhenNotRequested() {
        when(addressRepository.countByUserId(USER_ID)).thenReturn(0L);
        echoSave();

        AddressResponse response = service.create(USER_ID, request(false));

        // Otherwise checkout faces an address book with entries but no default.
        assertThat(response.isDefault()).isTrue();
    }

    @Test
    void aLaterAddressIsNotDefaultUnlessAsked() {
        when(addressRepository.countByUserId(USER_ID)).thenReturn(3L);
        echoSave();

        AddressResponse response = service.create(USER_ID, request(false));

        assertThat(response.isDefault()).isFalse();
        verify(addressRepository, never()).clearDefaultFor(anyLong());
    }

    @Test
    void phoneIsNormalisedOnCreate() {
        when(addressRepository.countByUserId(USER_ID)).thenReturn(1L);
        echoSave();

        AddressResponse response = service.create(USER_ID, request(false));

        assertThat(response.phone()).isEqualTo("+989121234567");
    }

    @Test
    void createIsRejectedOnceTheLimitIsReached() {
        when(addressRepository.countByUserId(USER_ID)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(USER_ID, request(false)))
            .isInstanceOf(BusinessRuleException.class);

        verify(addressRepository, never()).save(any());
    }

    /**
     * The incumbent default must be cleared and flushed <em>before</em> the new one is written.
     * Reversing these violates the partial unique index on {@code (user_id) WHERE is_default}.
     */
    @Test
    void promotingADefaultClearsTheIncumbentBeforeWriting() {
        when(addressRepository.countByUserId(USER_ID)).thenReturn(2L);
        echoSave();

        service.create(USER_ID, request(true));

        InOrder inOrder = inOrder(addressRepository);
        inOrder.verify(addressRepository).clearDefaultFor(USER_ID);
        inOrder.verify(addressRepository).flush();
        inOrder.verify(addressRepository).save(any(Address.class));
    }

    // --- ownership scoping ---------------------------------------------------------------------

    /**
     * Every read and write resolves by (id, userId). A bare findById plus a later ownership check
     * is the shape that becomes an IDOR the first time someone forgets the check.
     */
    @Test
    void anotherUsersAddressIsNotFound() {
        when(addressRepository.findByIdAndUserId(99L, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(OTHER_USER_ID, 99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingAnotherUsersAddressIsNotFound() {
        when(addressRepository.findByIdAndUserId(99L, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(OTHER_USER_ID, 99L, request(false)))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(addressRepository, never()).save(any());
    }

    @Test
    void deletingAnotherUsersAddressIsNotFound() {
        when(addressRepository.findByIdAndUserId(99L, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(OTHER_USER_ID, 99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(addressRepository, never()).delete(any());
    }

    // --- update / makeDefault ------------------------------------------------------------------

    @Test
    void updateCanPromoteToDefault() {
        Address existing = address(5L, USER_ID, false);
        when(addressRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(existing));
        echoSave();

        AddressResponse response = service.update(USER_ID, 5L, request(true));

        assertThat(response.isDefault()).isTrue();
        verify(addressRepository).clearDefaultFor(USER_ID);
    }

    /**
     * An address stops being the default by another one being promoted, not by editing this one
     * with the flag off — otherwise a routine edit silently leaves the user with no default.
     */
    @Test
    void updateDoesNotDemoteAnExistingDefault() {
        Address existing = address(5L, USER_ID, true);
        when(addressRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(existing));
        echoSave();

        AddressResponse response = service.update(USER_ID, 5L, request(false));

        assertThat(response.isDefault()).isTrue();
    }

    @Test
    void makeDefaultPromotesTheChosenAddress() {
        Address existing = address(5L, USER_ID, false);
        when(addressRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(existing));
        echoSave();

        AddressResponse response = service.makeDefault(USER_ID, 5L);

        assertThat(response.isDefault()).isTrue();
        verify(addressRepository).clearDefaultFor(USER_ID);
    }

    // --- delete --------------------------------------------------------------------------------

    @Test
    void deletingTheDefaultPromotesTheNextAddress() {
        Address defaultAddress = address(5L, USER_ID, true);
        Address survivor = address(6L, USER_ID, false);
        when(addressRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(defaultAddress));
        when(addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(USER_ID))
            .thenReturn(List.of(survivor));
        echoSave();

        service.delete(USER_ID, 5L);

        // A user with addresses but no default leaves checkout with nothing sensible to preselect.
        assertThat(survivor.isDefault()).isTrue();
        verify(addressRepository).save(survivor);
    }

    @Test
    void deletingANonDefaultAddressPromotesNothing() {
        Address plain = address(6L, USER_ID, false);
        when(addressRepository.findByIdAndUserId(6L, USER_ID)).thenReturn(Optional.of(plain));

        service.delete(USER_ID, 6L);

        verify(addressRepository).delete(plain);
        verify(addressRepository, never()).save(any());
    }

    @Test
    void deletingTheOnlyAddressLeavesNothingToPromote() {
        Address only = address(5L, USER_ID, true);
        when(addressRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(only));
        when(addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(USER_ID))
            .thenReturn(List.of());

        service.delete(USER_ID, 5L);

        verify(addressRepository).delete(only);
        verify(addressRepository, never()).save(any());
    }
}
