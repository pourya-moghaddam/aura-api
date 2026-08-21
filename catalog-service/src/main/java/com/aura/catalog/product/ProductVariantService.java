package com.aura.catalog.product;

import com.aura.catalog.color.ColorService;
import com.aura.catalog.inventory.Inventory;
import com.aura.catalog.inventory.InventoryRepository;
import com.aura.catalog.inventory.StockAvailability;
import com.aura.catalog.product.dto.VariantRequest;
import com.aura.catalog.product.dto.VariantResponse;
import com.aura.catalog.size.SizeService;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * The buyable combinations of a product.
 *
 * <p>Every write here ends by recomputing the product's denormalised price range and stock, because
 * those columns are what category pages sort and filter on — a variant added without refreshing
 * them leaves a product listed at the wrong price, and nothing about that fails loudly.
 */
@Service
@RequiredArgsConstructor
public class ProductVariantService {

    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final StockAvailability stockAvailability;
    private final ProductService productService;
    private final ColorService colorService;
    private final SizeService sizeService;

    @Transactional(readOnly = true)
    public List<VariantResponse> listFor(long userId, long productId) {
        productService.requireOwned(userId, productId);

        List<ProductVariant> variants = productVariantRepository.findByProductIdOrderByIdAsc(productId);
        // One lookup for the set rather than one per variant.
        Map<Long, Boolean> available =
            stockAvailability.byVariant(variants.stream().map(ProductVariant::getId).toList());

        return variants.stream()
            .map(variant -> VariantResponse.from(
                variant, Boolean.TRUE.equals(available.get(variant.getId()))))
            .toList();
    }

    @Transactional
    public VariantResponse add(long userId, long productId, VariantRequest request) {
        Product product = productService.requireOwned(userId, productId);

        validateAxes(product, request);
        requireCombinationFree(productId, request.colorId(), request.sizeId(), null);

        String sku = resolveSku(request.sku(), product, request);

        ProductVariant variant = ProductVariant.of(productId, request.colorId(), request.sizeId(),
            sku, request.price(), request.compareAtPrice());
        variant.setActive(request.isActive());

        ProductVariant saved = productVariantRepository.save(variant);

        // Every variant gets a stock row immediately, at zero. Creating it lazily on the first
        // stock edit would leave new variants missing from the seller's stock screen entirely -
        // present in the catalogue, absent from the list of things to stock, and therefore easy
        // to publish with no inventory behind them.
        inventoryRepository.save(Inventory.forVariant(saved.getId(), 0));

        // Not available, and no query needed to know it: the inventory row was just created at
        // zero on the line above.
        VariantResponse response = VariantResponse.from(saved, false);
        productService.refreshDerivedFields(productId);
        return response;
    }

    @Transactional
    public VariantResponse update(long userId, long productId, long variantId, VariantRequest request) {
        Product product = productService.requireOwned(userId, productId);
        ProductVariant variant = requireBelongingTo(productId, variantId);

        validateAxes(product, request);
        requireCombinationFree(productId, request.colorId(), request.sizeId(), variantId);

        variant.setColorId(request.colorId());
        variant.setSizeId(request.sizeId());
        variant.setPrice(request.price());
        variant.setCompareAtPrice(request.compareAtPrice());
        variant.setActive(request.isActive());
        if (request.sku() != null && !request.sku().isBlank()) {
            requireSkuFree(request.sku().trim(), variantId);
            variant.setSku(request.sku().trim());
        }
        variant.touch();

        // Editing a variant does not touch its stock, but the response still has to report the
        // truth rather than assume.
        VariantResponse response = VariantResponse.from(
            productVariantRepository.save(variant), stockAvailability.isBuyable(variantId));
        productService.refreshDerivedFields(productId);
        return response;
    }

    /**
     * Deleting the last variant would leave a published product with no price and nothing to add
     * to a cart, so it is refused. Deactivating is the way to withdraw one.
     */
    @Transactional
    public void delete(long userId, long productId, long variantId) {
        Product product = productService.requireOwned(userId, productId);
        ProductVariant variant = requireBelongingTo(productId, variantId);

        if (product.getStatus() == ProductStatus.ACTIVE
            && productVariantRepository.countByProductIdAndIsActiveTrue(productId) <= 1
            && variant.isActive()) {
            throw new BusinessRuleException("product-needs-a-variant",
                "A published product must keep at least one active variant. Archive the product "
                    + "instead, or deactivate this variant after adding another.");
        }

        productVariantRepository.delete(variant);
        productService.refreshDerivedFields(productId);
    }

    /**
     * A colour or size must exist, and a size must be one the product's category actually offers.
     *
     * <p>The second check is what stops shoe sizes appearing on a shirt: sizes are scoped to a
     * category and inherited down the tree, and a foreign key alone would happily accept "42" on a
     * T-shirt.
     */
    private void validateAxes(Product product, VariantRequest request) {
        if (request.colorId() != null) {
            colorService.require(request.colorId());
        }
        if (request.sizeId() != null) {
            sizeService.require(request.sizeId());

            boolean offered = sizeService.listForCategory(product.getCategoryId()).stream()
                .anyMatch(size -> size.id().equals(request.sizeId()));
            if (!offered) {
                throw new BusinessRuleException("size-not-for-category",
                    "That size is not available for this product's category.");
            }
        }
    }

    /**
     * Mirrors {@code UNIQUE NULLS NOT DISTINCT (product_id, color_id, size_id)} so a clash becomes
     * a message naming it rather than a constraint violation surfacing as a 500.
     */
    private void requireCombinationFree(Long productId, Long colorId, Long sizeId, Long excludingId) {
        productVariantRepository.findByCombination(productId, colorId, sizeId)
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("variant-combination-exists",
                    "This product already has a variant with that colour and size.");
            });
    }

    private void requireSkuFree(String sku, Long excludingId) {
        productVariantRepository.findBySku(sku)
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("sku-taken", "The SKU '" + sku + "' is already in use.");
            });
    }

    /**
     * Most sellers have no SKU scheme, but the column is unique and NOT NULL, so one has to exist.
     * Generated from the product slug and the combination, with a numeric suffix on the rare
     * collision — two products can legitimately produce the same stem.
     */
    private String resolveSku(String supplied, Product product, VariantRequest request) {
        if (supplied != null && !supplied.isBlank()) {
            String sku = supplied.trim();
            requireSkuFree(sku, null);
            return sku;
        }

        String stem = "%s-%s-%s".formatted(
            product.getSlug(),
            request.colorId() == null ? "x" : "c" + request.colorId(),
            request.sizeId() == null ? "x" : "s" + request.sizeId())
            .toUpperCase(Locale.ROOT);

        String candidate = truncate(stem);
        int suffix = 1;
        while (productVariantRepository.existsBySku(candidate)) {
            candidate = truncate(stem, "-" + suffix++);
        }
        return candidate;
    }

    private String truncate(String stem) {
        return truncate(stem, "");
    }

    private String truncate(String stem, String suffix) {
        int room = 100 - suffix.length();
        return (stem.length() > room ? stem.substring(0, room) : stem) + suffix;
    }

    private ProductVariant requireBelongingTo(long productId, long variantId) {
        return productVariantRepository.findById(variantId)
            .filter(variant -> variant.getProductId() == productId)
            .orElseThrow(() -> ResourceNotFoundException.of("Variant", variantId));
    }
}
