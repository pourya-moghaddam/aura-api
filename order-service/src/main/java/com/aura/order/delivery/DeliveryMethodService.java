package com.aura.order.delivery;

import com.aura.order.delivery.dto.DeliveryMethodRequest;
import com.aura.order.delivery.dto.DeliveryMethodResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Delivery methods, as an admin manages them and as checkout reads them.
 */
@Service
@RequiredArgsConstructor
public class DeliveryMethodService {

    private final DeliveryMethodRepository deliveryMethodRepository;

    /** What a shopper may choose at checkout. */
    @Transactional(readOnly = true)
    public List<DeliveryMethodResponse> listActive() {
        return deliveryMethodRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc().stream()
            .map(DeliveryMethodResponse::from).toList();
    }

    /** Everything, retired included — the admin screen has to show what exists to manage it. */
    @Transactional(readOnly = true)
    public List<DeliveryMethodResponse> listAll() {
        return deliveryMethodRepository.findAllByOrderBySortOrderAscNameAsc().stream()
            .map(DeliveryMethodResponse::from).toList();
    }

    @Transactional
    public DeliveryMethodResponse create(DeliveryMethodRequest request) {
        requireNameAvailable(request.name(), null);

        DeliveryMethod method = DeliveryMethod.of(request.name().trim(),
            trimmed(request.description()), request.fee(), request.sortOrder());
        method.setActive(request.isActive());

        return DeliveryMethodResponse.from(deliveryMethodRepository.save(method));
    }

    /**
     * Editing changes what future checkouts are quoted, and nothing else.
     *
     * <p>Orders store the name and fee they were charged rather than a reference, so raising a
     * price here cannot retroactively change what a past order says the customer paid. That is the
     * whole reason for the snapshot, and it is the thing to preserve if this ever grows into
     * versioned pricing.
     */
    @Transactional
    public DeliveryMethodResponse update(long id, DeliveryMethodRequest request) {
        DeliveryMethod method = require(id);
        requireNameAvailable(request.name(), id);

        method.setName(request.name().trim());
        method.setDescription(trimmed(request.description()));
        method.setFee(request.fee());
        method.setActive(request.isActive());
        method.setSortOrder(request.sortOrder());
        method.touch();

        return DeliveryMethodResponse.from(deliveryMethodRepository.save(method));
    }

    /**
     * Deactivates rather than deletes.
     *
     * <p>Orders reference the method by id as well as by snapshot, and the foreign key is
     * {@code ON DELETE RESTRICT} — so a delete would either fail on the first order that used it or,
     * worse, succeed for a method nobody had ordered with yet and behave differently later.
     * Deactivating is what an admin wants anyway: gone from checkout, still resolvable in history.
     */
    @Transactional
    public DeliveryMethodResponse deactivate(long id) {
        DeliveryMethod method = require(id);
        method.setActive(false);
        method.touch();
        return DeliveryMethodResponse.from(deliveryMethodRepository.save(method));
    }

    /**
     * Resolves a method for checkout, refusing anything a shopper should not be able to pick.
     *
     * <p>An inactive method has to be rejected here even though the list endpoint never offers it:
     * the id arrives in a request body, and a checkout replayed from a stale page or edited by
     * hand would otherwise ship at a price the shop has withdrawn.
     */
    @Transactional(readOnly = true)
    public DeliveryMethod requireSelectable(long id) {
        DeliveryMethod method = require(id);
        if (!method.isActive()) {
            throw new BusinessRuleException("delivery-method-unavailable",
                "That delivery method is no longer available. Please choose another.");
        }
        return method;
    }

    @Transactional(readOnly = true)
    public DeliveryMethod require(long id) {
        return deliveryMethodRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Delivery method", id));
    }

    private void requireNameAvailable(String name, Long excludingId) {
        deliveryMethodRepository.findByNameIgnoreCase(name.trim())
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("delivery-method-name-taken",
                    "A delivery method named '" + existing.getName() + "' already exists.");
            });
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
