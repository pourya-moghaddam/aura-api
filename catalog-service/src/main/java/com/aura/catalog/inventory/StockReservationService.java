package com.aura.catalog.inventory;

import com.aura.catalog.inventory.StockReservation.ReservationStatus;
import com.aura.catalog.inventory.dto.ReservationResponse;
import com.aura.catalog.inventory.dto.ReserveStockRequest;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds, commits and releases stock — the one cross-service transaction in the whole design.
 *
 * <p>The plan's §1.1 consolidation removes every other distributed transaction, but this one
 * cannot be merged away: order-service owns orders and catalog owns stock. It is handled with an
 * explicit hold and a TTL rather than a saga, and the reason that works is that the hold is
 * temporary by construction. Nobody has to write a compensating transaction, because doing nothing
 * at all — the case where order-service dies mid-checkout — already ends with the stock back on
 * the shelf.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {

    /**
     * How long a shopper has to complete payment. Long enough to enter card details at the gateway,
     * short enough that an abandoned checkout does not hold the last unit of something all evening.
     */
    static final Duration HOLD_DURATION = Duration.ofMinutes(15);

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository stockReservationRepository;

    /**
     * Holds stock for an order, all lines or none.
     *
     * <p>Idempotent on order id. A retried checkout — a double-clicked button, a network timeout
     * order-service retried through — must not take the stock twice, and returning the existing
     * hold is both correct and what the caller wanted anyway.
     */
    @Transactional
    public ReservationResponse reserve(ReserveStockRequest request) {
        // Taken before the idempotency check, not after. The check and the write that follows it
        // are a read-then-write pair, and two simultaneous retries of the same checkout would
        // otherwise both find nothing held and both take the stock - holding twice what the order
        // asked for. The inventory row lock further down does not help: at this point there is no
        // reservation row yet to contend on.
        stockReservationRepository.lockOrder(request.orderId());

        List<StockReservation> existing =
            stockReservationRepository.findByOrderIdAndStatus(request.orderId(), ReservationStatus.HELD);
        if (!existing.isEmpty()) {
            log.debug("Order {} already holds stock; returning the existing reservation", request.orderId());
            return toResponse(request.orderId(), existing);
        }
        // A committed order asking to reserve again is a different matter: the stock is gone and
        // re-taking it would sell the same units twice.
        if (stockReservationRepository.existsByOrderIdAndStatus(request.orderId(), ReservationStatus.COMMITTED)) {
            throw new ConflictException("order-already-committed",
                "This order's stock has already been committed.");
        }

        Map<Long, Integer> wanted = collapse(request.lines());

        // Locks every row up front, in variant-id order. Holding all the locks before changing
        // anything is what makes the all-or-nothing guarantee real rather than aspirational.
        Map<Long, Inventory> locked = inventoryRepository.lockForUpdate(wanted.keySet()).stream()
            .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));

        List<String> shortfalls = new ArrayList<>();
        wanted.forEach((variantId, quantity) -> {
            Inventory inventory = locked.get(variantId);
            if (inventory == null) {
                // No inventory row means the variant was never stocked, which is indistinguishable
                // from out of stock as far as the shopper is concerned.
                shortfalls.add("variant " + variantId + " is not available");
            } else if (inventory.available() < quantity) {
                shortfalls.add("variant " + variantId + " has only " + inventory.available()
                    + " left, " + quantity + " requested");
            }
        });

        if (!shortfalls.isEmpty()) {
            // Rejecting the whole request rather than reserving what fits: a partially filled
            // order is not what anyone asked for, and order-service would have to unpick it.
            throw new BusinessRuleException("insufficient-stock",
                "Not enough stock: " + String.join("; ", shortfalls));
        }

        OffsetDateTime expiresAt = OffsetDateTime.now().plus(HOLD_DURATION);
        List<StockReservation> held = new ArrayList<>();

        wanted.forEach((variantId, quantity) -> {
            Inventory inventory = locked.get(variantId);
            inventory.setQuantityReserved(inventory.getQuantityReserved() + quantity);
            inventory.touch();
            held.add(StockReservation.hold(variantId, request.orderId(), quantity, expiresAt));
        });

        inventoryRepository.saveAll(locked.values());
        stockReservationRepository.saveAll(held);

        log.info("Held stock for order {} across {} variant(s) until {}",
            request.orderId(), held.size(), expiresAt);

        return toResponse(request.orderId(), held);
    }

    /**
     * Payment succeeded: the held stock leaves the building.
     *
     * <p>Both counters drop. On-hand because the units are gone, reserved because they are no
     * longer merely spoken for — miss the second and every sale permanently shrinks what the
     * variant appears to have available.
     */
    @Transactional
    public void commit(long orderId) {
        List<StockReservation> held =
            stockReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD);

        if (held.isEmpty()) {
            // Either already committed, or the hold expired before payment cleared. Both are
            // reachable in normal operation - a shopper who pays at minute sixteen - and neither
            // is this method's problem to solve, but silently doing nothing would hide the second.
            if (stockReservationRepository.existsByOrderIdAndStatus(orderId, ReservationStatus.COMMITTED)) {
                log.debug("Order {} is already committed; nothing to do", orderId);
                return;
            }
            throw new BusinessRuleException("no-held-stock",
                "No stock is being held for this order. The hold may have expired.");
        }

        applyToInventory(held, (inventory, reservation) -> {
            inventory.setQuantityOnHand(inventory.getQuantityOnHand() - reservation.getQuantity());
            inventory.setQuantityReserved(inventory.getQuantityReserved() - reservation.getQuantity());
        });

        held.forEach(reservation -> reservation.settle(ReservationStatus.COMMITTED));
        stockReservationRepository.saveAll(held);

        log.info("Committed stock for order {}", orderId);
    }

    /**
     * Payment failed or the shopper walked away: the stock goes back on the shelf.
     *
     * <p>Only {@code quantity_reserved} moves — the units never left.
     */
    @Transactional
    public void release(long orderId) {
        List<StockReservation> held =
            stockReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD);

        if (held.isEmpty()) {
            // Idempotent by design. Order-service retries this, and the expiry sweep may well have
            // got there first; neither should be an error.
            log.debug("No held stock for order {}; nothing to release", orderId);
            return;
        }

        releaseAll(held);
        log.info("Released stock for order {}", orderId);
    }

    /**
     * The safety net. Releases holds whose time ran out.
     *
     * <p>This is what makes the whole arrangement correct without a saga: even if order-service
     * crashes between reserving and paying, or the shopper closes the tab at the gateway, the
     * stock comes back on its own. Nothing has to remember.
     */
    @Transactional
    public int releaseExpired(int batchSize) {
        List<StockReservation> expired =
            stockReservationRepository.claimExpired(OffsetDateTime.now(), batchSize);

        if (expired.isEmpty()) {
            return 0;
        }

        releaseAll(expired);
        log.info("Released {} expired stock reservation(s)", expired.size());
        return expired.size();
    }

    private void releaseAll(List<StockReservation> reservations) {
        applyToInventory(reservations, (inventory, reservation) ->
            inventory.setQuantityReserved(inventory.getQuantityReserved() - reservation.getQuantity()));

        reservations.forEach(reservation -> reservation.settle(ReservationStatus.RELEASED));
        stockReservationRepository.saveAll(reservations);
    }

    /**
     * Locks the affected inventory rows and applies a change to each.
     *
     * <p>Locking here too, not just on the reserve path. Committing an order and releasing an
     * expired hold on the same variant are separate transactions that both read-modify-write the
     * same counters, and without the lock one can overwrite the other's arithmetic.
     */
    private void applyToInventory(List<StockReservation> reservations,
                                  java.util.function.BiConsumer<Inventory, StockReservation> change) {
        List<Long> variantIds = reservations.stream()
            .map(StockReservation::getVariantId).distinct().toList();

        Map<Long, Inventory> locked = inventoryRepository.lockForUpdate(variantIds).stream()
            .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));

        for (StockReservation reservation : reservations) {
            Inventory inventory = locked.get(reservation.getVariantId());
            if (inventory == null) {
                // The variant was deleted under a live hold. The reservation still has to be
                // settled or the sweep will keep picking it up forever.
                log.warn("Reservation {} refers to variant {} which has no inventory row",
                    reservation.getId(), reservation.getVariantId());
                continue;
            }
            change.accept(inventory, reservation);
            inventory.touch();
        }

        inventoryRepository.saveAll(locked.values());
    }

    /** Merges repeated lines for one variant, so a cart listing it twice reserves the total once. */
    private Map<Long, Integer> collapse(List<ReserveStockRequest.Line> lines) {
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (ReserveStockRequest.Line line : lines) {
            wanted.merge(line.variantId(), line.quantity(), Integer::sum);
        }
        return wanted;
    }

    private ReservationResponse toResponse(long orderId, List<StockReservation> reservations) {
        return new ReservationResponse(
            orderId,
            reservations.stream().map(ReservationResponse.Held::from).toList(),
            reservations.getFirst().getExpiresAt());
    }
}
