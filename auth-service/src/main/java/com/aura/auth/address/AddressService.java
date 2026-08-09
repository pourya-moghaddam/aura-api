package com.aura.auth.address;

import com.aura.auth.address.dto.AddressRequest;
import com.aura.auth.address.dto.AddressResponse;
import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The signed-in user's address book.
 *
 * <p>Every method takes the {@code userId} from the security context, never from the request body,
 * and every lookup is scoped by it. That is the whole access-control story for this resource: there
 * is no path here that resolves an address by id alone.
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    /**
     * A ceiling exists mainly so a script cannot write unbounded rows into another user's quota of
     * the shared table. Generous enough that no real person will notice it.
     */
    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addressRepository;

    @Transactional(readOnly = true)
    public List<AddressResponse> listFor(long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
            .map(AddressResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse getFor(long userId, long addressId) {
        return AddressResponse.from(requireOwned(userId, addressId));
    }

    @Transactional
    public AddressResponse create(long userId, AddressRequest request) {
        long existingCount = addressRepository.countByUserId(userId);
        if (existingCount >= MAX_ADDRESSES_PER_USER) {
            throw new BusinessRuleException("address-limit-reached",
                "You can save at most " + MAX_ADDRESSES_PER_USER + " addresses.");
        }

        Address address = new Address();
        address.setUserId(userId);
        apply(request, address);

        // The first address a user saves becomes their default regardless of what was asked for.
        // Otherwise checkout has to cope with an address book that has entries but no default, for
        // no reason the user would understand.
        setDefaultFlag(userId, address, request.isDefault() || existingCount == 0);

        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(long userId, long addressId, AddressRequest request) {
        Address address = requireOwned(userId, addressId);
        apply(request, address);

        // An update may promote this address to default, but must not silently demote it: clearing
        // the flag is done by promoting a different address, not by editing this one.
        if (request.isDefault() && !address.isDefault()) {
            setDefaultFlag(userId, address, true);
        }

        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse makeDefault(long userId, long addressId) {
        Address address = requireOwned(userId, addressId);
        setDefaultFlag(userId, address, true);
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public void delete(long userId, long addressId) {
        Address address = requireOwned(userId, addressId);
        boolean wasDefault = address.isDefault();
        addressRepository.delete(address);

        // Deleting the default would otherwise leave a user with several addresses and no default,
        // which checkout has no sensible way to resolve. Promote the next one instead.
        if (wasDefault) {
            addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                .findFirst()
                .ifPresent(next -> {
                    next.setDefault(true);
                    addressRepository.save(next);
                });
        }
    }

    /**
     * Demotes the incumbent default before promoting this one. Required by the partial unique index
     * on {@code (user_id) WHERE is_default} — two defaults is a constraint violation, not a
     * last-write-wins situation.
     *
     * <p>The flush between the two writes is deliberate: without it Hibernate is free to order the
     * INSERT of the new default before the UPDATE that clears the old one, and the constraint fires
     * on a state the code never intended to create.
     */
    private void setDefaultFlag(long userId, Address address, boolean makeDefault) {
        if (!makeDefault) {
            address.setDefault(false);
            return;
        }

        addressRepository.clearDefaultFor(userId);
        addressRepository.flush();
        address.setDefault(true);
    }

    private void apply(AddressRequest request, Address address) {
        address.setTitle(request.title());
        address.setRecipientFirstName(request.recipientFirstName());
        address.setRecipientLastName(request.recipientLastName());
        // Normalised for the same reason as the account phone: the recipient may later be matched
        // against a user, and 0912… must not be a different number from +98912…
        address.setPhone(IranianPhoneNumber.normalize(request.phone()));
        address.setProvince(request.province());
        address.setCity(request.city());
        address.setLine1(request.line1());
        address.setLine2(request.line2());
        address.setPostalCode(request.postalCode());
    }

    private Address requireOwned(long userId, long addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Address", addressId));
    }
}
