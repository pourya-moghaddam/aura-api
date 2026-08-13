package com.aura.order.order;

import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.cart.Cart;
import com.aura.order.cart.CartItem;
import com.aura.order.cart.CartItemRepository;
import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartRepository;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import com.aura.order.delivery.DeliveryMethod;
import com.aura.order.delivery.DeliveryMethodService;
import com.aura.order.discount.DiscountService;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a basket into an order.
 *
 * <p>The ordering inside the transaction is the whole design, and it is chosen around one
 * question: which way should this fail? Prices and stock are re-read from catalog rather than
 * trusted from the cart, because minutes may have passed on the address form. The order row is
 * written before the stock is held, because the reservation is keyed by order id. And the remote
 * reservation is the <em>last</em> thing done, so a failure anywhere else rolls the order back
 * before any stock has moved.
 *
 * <p>If the transaction rolls back after the reservation succeeded, the hold is orphaned and
 * catalog's TTL sweep returns it within the quarter-hour. That is the failure this is arranged to
 * prefer: stock held briefly for nobody, rather than an order that exists with nothing held for
 * it — which is an oversell, and is discovered by a customer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    /** A trace code is 32^10; a clash means the random source is broken, not that we were unlucky. */
    private static final int TRACE_CODE_ATTEMPTS = 5;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogGateway catalogGateway;
    private final DeliveryMethodService deliveryMethodService;
    private final DiscountService discountService;

    @Transactional
    public OrderResponse checkout(CartOwner owner, CheckoutRequest request) {
        String idempotencyKey = blankToNull(request.idempotencyKey());

        if (idempotencyKey != null) {
            // Taken before the lookup, not after: two tabs submitting at once both miss the
            // lookup otherwise, and the second is refused by the unique index - a 500 on exactly
            // the retry the key exists to make safe.
            orderRepository.lockIdempotencyKey(idempotencyKey);

            Order existing = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                log.info("Checkout replayed with key {}; returning order {}",
                    idempotencyKey, existing.getTraceCode());
                return OrderResponse.of(existing,
                    orderItemRepository.findByOrderIdOrderByIdAsc(existing.getId()));
            }
        }

        Cart cart = findCart(owner)
            .orElseThrow(() -> new BusinessRuleException("cart-empty",
                "Your basket is empty."));

        List<CartItem> cartItems = cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(cart.getId());
        if (cartItems.isEmpty()) {
            throw new BusinessRuleException("cart-empty", "Your basket is empty.");
        }

        // Priced and checked against catalog now, not against what the cart remembers. The shopper
        // has been filling in an address; the shop may have moved on.
        Map<Long, VariantSnapshot> snapshots = catalogGateway.snapshotsFor(
            cartItems.stream().map(CartItem::getVariantId).toList());
        requireAllPurchasable(cartItems, snapshots);

        DeliveryMethod delivery = deliveryMethodService.requireSelectable(request.deliveryMethodId());

        long subtotal = cartItems.stream()
            .mapToLong(item -> snapshots.get(item.getVariantId()).unitPrice() * item.getQuantity())
            .sum();

        Order order = orderRepository.saveAndFlush(
            newOrder(owner, request, delivery, subtotal, idempotencyKey));

        List<OrderItem> items = cartItems.stream()
            .map(item -> toOrderItem(order.getId(), item, snapshots.get(item.getVariantId())))
            .toList();
        orderItemRepository.saveAll(items);

        // After the order exists, because the redemption row references it - and inside this
        // transaction, so an abandoned checkout cannot spend a use of a limited code.
        applyDiscount(order, request.discountCode(), subtotal);

        order.setTotal(order.getSubtotal() - order.getDiscountAmount() + order.getDeliveryFee());
        order.touch();
        orderRepository.save(order);

        // Last, and remote. Everything above can still be rolled back without a trace.
        catalogGateway.reserveStock(order.getId(), items.stream()
            .map(item -> new CatalogGateway.StockLine(item.getVariantId(), item.getQuantity()))
            .toList());

        // The basket has become an order. Leaving it would show the shopper their items still
        // waiting to be bought while they are on the payment page.
        cartItemRepository.deleteByCartId(cart.getId());
        cartRepository.delete(cart);

        log.info("Order {} placed: {} item(s), {} Rial, {}", order.getTraceCode(), items.size(),
            order.getTotal(), owner.isUser() ? "user " + owner.userId() : "guest");

        return OrderResponse.of(order, items);
    }

    // --- reads ---------------------------------------------------------------------------------

    /**
     * Looks an order up by the code the customer quotes.
     *
     * <p>Anonymous by design: a guest has no account to sign into, and the trace code <em>is</em>
     * the credential — which is why it is ten random characters rather than the primary key.
     */
    @Transactional(readOnly = true)
    public OrderResponse byTraceCode(String traceCode) {
        Order order = orderRepository.findByTraceCode(TraceCodes.normalise(traceCode))
            .orElseThrow(() -> ResourceNotFoundException.of("Order", traceCode));

        return OrderResponse.of(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()));
    }

    /** A signed-in shopper's order history, newest first. */
    @Transactional(readOnly = true)
    public List<OrderResponse> forUser(long userId) {
        List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(
            userId, org.springframework.data.domain.PageRequest.of(0, 50)).getContent();

        // One query for every order's items rather than one per order.
        Map<Long, List<OrderItem>> byOrder = orderItemRepository
            .findByOrderIdInOrderByIdAsc(orders.stream().map(Order::getId).toList())
            .stream()
            .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));

        return orders.stream()
            .map(order -> OrderResponse.of(order, byOrder.getOrDefault(order.getId(), List.of())))
            .toList();
    }

    // --- building ------------------------------------------------------------------------------

    private Order newOrder(CartOwner owner, CheckoutRequest request, DeliveryMethod delivery,
                           long subtotal, String idempotencyKey) {
        Order order = new Order();
        order.setTraceCode(uniqueTraceCode());
        order.setUserId(owner.isUser() ? owner.userId() : null);

        order.setBuyerFirstName(request.buyerFirstName().trim());
        order.setBuyerLastName(request.buyerLastName().trim());
        order.setBuyerPhone(IranianPhoneNumber.tryNormalize(request.buyerPhone())
            .orElseThrow(() -> new BusinessRuleException("invalid-phone",
                "That does not look like an Iranian mobile number.")));

        // A map rather than a typed address entity: this is a snapshot for display and for the
        // courier, and it must keep rendering unchanged if the address model ever grows a field.
        Map<String, String> address = new LinkedHashMap<>();
        address.put("province", request.province().trim());
        address.put("city", request.city().trim());
        address.put("addressLine", request.addressLine().trim());
        address.put("postalCode", request.postalCode().trim());
        order.setAddressSnapshot(address);
        order.setPostalCode(request.postalCode().trim());

        order.setDeliveryMethodId(delivery.getId());
        order.setDeliveryName(delivery.getName());
        order.setDeliveryFee(delivery.getFee());

        order.setSubtotal(subtotal);
        order.setDiscountAmount(0L);
        // Provisional, and never observed: the discount is applied and the total corrected before
        // this transaction commits. The column is NOT NULL, so it needs a value to be flushed at
        // all, and flushing is what yields the id the redemption and the reservation both need.
        order.setTotal(subtotal + delivery.getFee());

        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setDerivedStatus(FulfillmentStatus.PENDING);
        order.setSource(OrderSource.CUSTOMER);
        order.setIdempotencyKey(idempotencyKey);

        order.setCreatedAt(OffsetDateTime.now());
        order.setUpdatedAt(order.getCreatedAt());
        return order;
    }

    private OrderItem toOrderItem(Long orderId, CartItem cartItem, VariantSnapshot snapshot) {
        Map<String, String> variant = new LinkedHashMap<>();
        if (snapshot.colorName() != null) {
            variant.put("color", snapshot.colorName());
        }
        if (snapshot.sizeName() != null) {
            variant.put("size", snapshot.sizeName());
        }
        if (snapshot.productSlug() != null) {
            variant.put("slug", snapshot.productSlug());
        }

        return OrderItem.of(orderId, snapshot.productId(), snapshot.variantId(),
            snapshot.sellerId(), snapshot.productName(), variant,
            snapshot.unitPrice(), cartItem.getQuantity());
    }

    private void applyDiscount(Order order, String code, long subtotal) {
        if (code == null || code.isBlank()) {
            return;
        }
        DiscountService.Redemption redemption =
            discountService.redeem(code, subtotal, order.getUserId(), order.getId());

        order.setDiscountAmount(redemption.amount());
        order.setDiscountCode(redemption.code());
        order.setDiscountCodeId(redemption.discountCodeId());
    }

    /**
     * Refuses the whole checkout if any line has become unbuyable.
     *
     * <p>All of them are named at once rather than one per attempt: a shopper made to discover
     * their problems one submission at a time will abandon the basket before the third.
     */
    private void requireAllPurchasable(List<CartItem> cartItems,
                                       Map<Long, VariantSnapshot> snapshots) {
        List<String> problems = new ArrayList<>();

        for (CartItem item : cartItems) {
            VariantSnapshot snapshot = snapshots.get(item.getVariantId());

            if (snapshot == null || !snapshot.purchasable()) {
                problems.add((snapshot == null ? "An item" : snapshot.productName())
                    + " is no longer sold.");
            } else if (snapshot.available() < item.getQuantity()) {
                problems.add(snapshot.productName() + ": only " + Math.max(snapshot.available(), 0)
                    + " left in stock.");
            }
        }

        if (!problems.isEmpty()) {
            throw new BusinessRuleException("cart-not-purchasable",
                "Your basket has changed. " + String.join(" ", problems));
        }
    }

    private String uniqueTraceCode() {
        for (int attempt = 0; attempt < TRACE_CODE_ATTEMPTS; attempt++) {
            String candidate = TraceCodes.generate();
            if (!orderRepository.existsByTraceCode(candidate)) {
                return candidate;
            }
            log.warn("Trace code collision on attempt {} — improbable enough to be worth noticing",
                attempt + 1);
        }
        throw new IllegalStateException("Could not generate a unique trace code");
    }

    private java.util.Optional<Cart> findCart(CartOwner owner) {
        return owner.isUser()
            ? cartRepository.findByUserId(owner.userId())
            : cartRepository.findByCartToken(owner.cartToken());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
