package com.aura.order.cart;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.cart.dto.CartResponse;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Carts, for shoppers with and without accounts — requirement 12.
 *
 * <p>Prices are read from catalog on every view rather than trusted from the row. The stored
 * {@code priceAtAdd} exists only to tell a shopper that something changed while it sat in their
 * basket; charging it would mean honouring a price the shop has moved on from, and the seller
 * never agreed to that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /**
     * A ceiling on distinct lines, not on quantity. Its job is to stop a script filling the table
     * with one cart, not to tell a real shopper they have bought enough.
     */
    private static final int MAX_LINES = 100;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogGateway catalogGateway;

    // --- reads ---------------------------------------------------------------------------------

    /**
     * The cart as it stands, priced against catalog right now.
     *
     * <p>Returns an empty cart rather than a 404 when there is none: "you have no cart yet" and
     * "your cart is empty" are the same thing to a shopper, and inventing a row just to answer a
     * GET would leave a trail of empty carts behind every visitor who never bought anything.
     */
    @Transactional(readOnly = true)
    public CartResponse view(CartOwner owner) {
        return find(owner)
            .map(cart -> priced(cart, cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(cart.getId())))
            .orElseGet(CartResponse::empty);
    }

    /**
     * The basket in the terms a discount is judged in.
     *
     * <p>Priced against catalog like everything else here, and carrying each line's category path
     * so a scoped code can be matched. An empty list for a shopper with no basket, which is what
     * makes quoting a code before adding anything answer "nothing to apply it to" rather than fail.
     */
    @Transactional(readOnly = true)
    public List<com.aura.order.discount.DiscountLine> discountLines(CartOwner owner) {
        return find(owner)
            .map(cart -> {
                List<CartItem> items = cartItemRepository
                    .findByCartIdOrderByAddedAtAscIdAsc(cart.getId());
                Map<Long, VariantSnapshot> snapshots = catalogGateway.snapshotsFor(
                    items.stream().map(CartItem::getVariantId).toList());

                return items.stream()
                    .map(item -> java.util.Optional.ofNullable(snapshots.get(item.getVariantId()))
                        // A withdrawn line is worth nothing towards a discount, exactly as it is
                        // worth nothing towards the subtotal.
                        .filter(VariantSnapshot::purchasable)
                        .map(snapshot -> snapshot.toDiscountLine(item.getQuantity())))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            })
            .orElseGet(List::of);
    }

    // --- writes --------------------------------------------------------------------------------

    /**
     * Adds a variant, or raises the quantity if it is already there.
     *
     * <p>Adding the same thing twice meaning "two of them" rather than "two lines" is both what a
     * shopper expects and what the {@code (cart_id, variant_id)} unique constraint requires.
     */
    @Transactional
    public CartResponse addItem(CartOwner owner, long variantId, int quantity) {
        requirePositive(quantity);

        VariantSnapshot snapshot = requirePurchasable(variantId);

        // Everything is validated before a cart is created. Creating one first and then rejecting
        // the line leaves an empty cart behind for every failed add - the same litter the view
        // path deliberately avoids, arrived at from the other direction.
        Optional<Cart> found = find(owner);
        Optional<CartItem> existing = found
            .flatMap(cart -> cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId));

        int desired = existing.map(CartItem::getQuantity).orElse(0) + quantity;
        requireAvailable(snapshot, desired);

        Cart cart = found.orElseGet(() -> cartRepository.save(owner.isUser()
            ? Cart.forUser(owner.userId())
            : Cart.forGuest(owner.cartToken())));

        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(desired);
            item.touch();
            cartItemRepository.save(item);
        } else {
            if (cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(cart.getId()).size() >= MAX_LINES) {
                throw new BusinessRuleException("cart-too-large",
                    "A cart can hold at most " + MAX_LINES + " different items.");
            }
            cartItemRepository.save(CartItem.of(cart.getId(), snapshot.productId(), variantId,
                quantity, snapshot.unitPrice()));
        }

        return touchAndPrice(cart);
    }

    /** Sets a line's quantity outright. Zero removes it, which is what a quantity box of 0 means. */
    @Transactional
    public CartResponse setQuantity(CartOwner owner, long variantId, int quantity) {
        Cart cart = requireCart(owner);
        CartItem item = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId)
            .orElseThrow(() -> ResourceNotFoundException.of("Cart item", variantId));

        if (quantity <= 0) {
            cartItemRepository.delete(item);
            return touchAndPrice(cart);
        }

        requireAvailable(requirePurchasable(variantId), quantity);

        item.setQuantity(quantity);
        item.touch();
        cartItemRepository.save(item);

        return touchAndPrice(cart);
    }

    @Transactional
    public CartResponse removeItem(CartOwner owner, long variantId) {
        Cart cart = requireCart(owner);
        cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId)
            .ifPresent(cartItemRepository::delete);

        return touchAndPrice(cart);
    }

    @Transactional
    public CartResponse clear(CartOwner owner) {
        Cart cart = requireCart(owner);
        cartItemRepository.deleteByCartId(cart.getId());
        return touchAndPrice(cart);
    }

    /**
     * Folds a guest cart into the signed-in shopper's, called immediately after login.
     *
     * <p>The step most implementations forget, and its absence is very visible: the shopper signs
     * in and their basket appears to vanish. Quantities are summed for anything in both, capped at
     * what is actually available, and the guest cart is deleted so the token cannot resurrect it.
     *
     * <p>Idempotent — a repeated call finds no guest cart and returns the account's unchanged.
     */
    @Transactional
    public CartResponse mergeGuestCartInto(long userId, UUID guestToken) {
        Optional<Cart> guestCart = cartRepository.findByCartToken(guestToken);
        if (guestCart.isEmpty()) {
            return view(CartOwner.user(userId));
        }

        Cart guest = guestCart.get();
        Cart target = cartRepository.findByUserId(userId)
            .orElseGet(() -> cartRepository.save(Cart.forUser(userId)));

        List<CartItem> guestItems = cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(guest.getId());

        if (!guestItems.isEmpty()) {
            Map<Long, VariantSnapshot> snapshots = catalogGateway.snapshotsFor(
                guestItems.stream().map(CartItem::getVariantId).toList());

            for (CartItem guestItem : guestItems) {
                VariantSnapshot snapshot = snapshots.get(guestItem.getVariantId());
                if (snapshot == null || !snapshot.purchasable()) {
                    // Withdrawn while the guest was away. Dropping it silently is kinder than
                    // failing the whole merge and losing everything else in the basket.
                    log.debug("Dropping unavailable variant {} while merging cart", guestItem.getVariantId());
                    continue;
                }

                Optional<CartItem> mine = cartItemRepository.findByCartIdAndVariantId(
                    target.getId(), guestItem.getVariantId());

                // Capped at what exists: the two carts summed can easily exceed stock, and
                // carrying an impossible quantity into checkout only fails later and less clearly.
                int merged = Math.min(
                    mine.map(CartItem::getQuantity).orElse(0) + guestItem.getQuantity(),
                    Math.max(snapshot.available(), 0));

                if (merged <= 0) {
                    continue;
                }

                if (mine.isPresent()) {
                    CartItem item = mine.get();
                    item.setQuantity(merged);
                    item.touch();
                    cartItemRepository.save(item);
                } else {
                    cartItemRepository.save(CartItem.of(target.getId(), guestItem.getProductId(),
                        guestItem.getVariantId(), merged, snapshot.unitPrice()));
                }
            }
        }

        // The guest cart goes, so the cookie cannot be replayed to reach a basket that is now the
        // account's. Items cascade with it.
        cartRepository.delete(guest);

        log.info("Merged guest cart {} into user {}'s cart", guest.getId(), userId);
        return touchAndPrice(target);
    }

    /** Reclaims baskets nobody came back for. Guest carts only — an account's cart is theirs. */
    @Transactional
    public int sweepAbandonedGuestCarts(java.time.OffsetDateTime before) {
        int removed = cartRepository.deleteGuestCartsUpdatedBefore(before);
        if (removed > 0) {
            log.info("Swept {} abandoned guest cart(s)", removed);
        }
        return removed;
    }

    // --- shared --------------------------------------------------------------------------------

    private Optional<Cart> find(CartOwner owner) {
        return owner.isUser()
            ? cartRepository.findByUserId(owner.userId())
            : cartRepository.findByCartToken(owner.cartToken());
    }

    private Cart findOrCreate(CartOwner owner) {
        return find(owner).orElseGet(() -> cartRepository.save(owner.isUser()
            ? Cart.forUser(owner.userId())
            : Cart.forGuest(owner.cartToken())));
    }

    /** For operations on an existing line: there is nothing to remove from a cart that never was. */
    private Cart requireCart(CartOwner owner) {
        return find(owner).orElseThrow(() -> ResourceNotFoundException.of("Cart", "current"));
    }

    private VariantSnapshot requirePurchasable(long variantId) {
        VariantSnapshot snapshot = catalogGateway.snapshotsFor(List.of(variantId)).get(variantId);

        if (snapshot == null || !snapshot.purchasable()) {
            throw new BusinessRuleException("variant-not-purchasable",
                "That item is no longer available.");
        }
        return snapshot;
    }

    private void requireAvailable(VariantSnapshot snapshot, int desired) {
        if (snapshot.available() < desired) {
            // Named separately from "not purchasable": out of stock might resolve itself, and a
            // shopper reads the two differently.
            throw new BusinessRuleException("insufficient-stock",
                snapshot.available() <= 0
                    ? "That item is out of stock."
                    : "Only " + snapshot.available() + " left in stock.");
        }
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BusinessRuleException("invalid-quantity", "Quantity must be at least one.");
        }
    }

    private CartResponse touchAndPrice(Cart cart) {
        cart.touch();
        cartRepository.save(cart);
        return priced(cart, cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(cart.getId()));
    }

    /**
     * Prices the cart against catalog as it is now, flagging lines whose price moved or whose
     * stock no longer covers the quantity.
     */
    private CartResponse priced(Cart cart, List<CartItem> items) {
        if (items.isEmpty()) {
            return CartResponse.empty();
        }
        Map<Long, VariantSnapshot> snapshots = catalogGateway.snapshotsFor(
            items.stream().map(CartItem::getVariantId).toList());

        return CartResponse.of(cart, items, snapshots);
    }
}
