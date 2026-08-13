package com.aura.catalog.product;

import com.aura.catalog.category.CategoryService;
import com.aura.catalog.product.dto.ProductMediaResponse;
import com.aura.catalog.product.dto.ProductRequest;
import com.aura.catalog.product.dto.ProductResponse;
import com.aura.catalog.product.dto.VariantResponse;
import com.aura.catalog.support.Slugs;
import com.aura.common.security.CurrentUser;
import com.aura.common.security.Roles;
import com.aura.common.web.PageResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Products — requirement 7.
 *
 * <p>Two rules run through everything here. A product may only hang off a leaf category, because
 * the storefront's whole navigation assumes browsing a parent means browsing everything beneath it.
 * And a seller may only touch their own products: ownership is checked on every write, not just on
 * the ones where it seemed likely to matter.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductMediaRepository productMediaRepository;
    private final ProductFieldValueRepository productFieldValueRepository;
    private final ProductFieldValueService productFieldValueService;
    private final ProductEventPublisher productEventPublisher;
    private final CategoryService categoryService;

    // --- reads ---------------------------------------------------------------------------------

    /** A storefront product page. Only ACTIVE products are reachable this way. */
    @Transactional(readOnly = true)
    public ProductResponse getPublishedBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
            .filter(candidate -> candidate.getStatus().isVisibleToShoppers())
            // A draft or archived product is a 404 to a shopper, not a 403: its existence is not
            // something the storefront should confirm.
            .orElseThrow(() -> ResourceNotFoundException.of("Product", slug));

        return withDetail(product);
    }

    /** Requirement 13: a category page, including everything filed beneath it. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listPublishedInCategory(long categoryId, Pageable pageable) {
        categoryService.require(categoryId);
        return summarise(productRepository.findActiveInCategoryTree(categoryId, pageable));
    }

    /** Requirement 8's first half: a seller's own catalogue, drafts included. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listForSeller(long sellerId, Pageable pageable) {
        return summarise(productRepository.findBySellerIdOrderByUpdatedAtDesc(sellerId, pageable));
    }

    @Transactional(readOnly = true)
    public ProductResponse getForSeller(long userId, long productId) {
        return withDetail(requireOwned(userId, productId));
    }

    // --- writes --------------------------------------------------------------------------------

    @Transactional
    public ProductResponse create(long sellerId, ProductRequest request) {
        // Enforced by a database trigger as well. This exists so the seller gets a message naming
        // the problem instead of a constraint violation surfacing as a 500.
        categoryService.requireLeaf(request.categoryId());

        String slug = resolveSlug(request.slug(), request.name(), null);

        Product product = Product.draft(sellerId, request.categoryId(), request.name().trim(), slug,
            request.description());
        Product saved = productRepository.save(product);

        productFieldValueService.replace(saved, request.fieldValues());

        Product stored = productRepository.save(saved);
        productEventPublisher.productChanged(stored);
        return withDetail(stored);
    }

    @Transactional
    public ProductResponse update(long userId, long productId, ProductRequest request) {
        Product product = requireOwned(userId, productId);

        boolean categoryChanged = !product.getCategoryId().equals(request.categoryId());
        if (categoryChanged) {
            categoryService.requireLeaf(request.categoryId());
            product.setCategoryId(request.categoryId());
        }

        product.setName(request.name().trim());
        product.setSlug(resolveSlug(request.slug(), request.name(), productId));
        product.setDescription(request.description());
        product.touch();

        // Always re-run, even when the category did not change: the admin may have added a
        // required field since this product was last saved, and a silent partial answer is worse
        // than a rejection the seller can act on.
        productFieldValueService.replace(product, request.fieldValues());

        Product stored = productRepository.save(product);
        productEventPublisher.productChanged(stored);
        return withDetail(stored);
    }

    /**
     * Makes a product visible to shoppers.
     *
     * <p>Refused unless there is something to sell and something to show. A live product with no
     * active variant has no price and cannot be added to a cart; one with no image is a blank tile
     * in every listing. Both are states a seller reaches by saving a draft halfway.
     */
    @Transactional
    public ProductResponse publish(long userId, long productId) {
        Product product = requireOwned(userId, productId);

        if (productVariantRepository.countByProductIdAndIsActiveTrue(productId) == 0) {
            throw new BusinessRuleException("product-has-no-variants",
                "Add at least one active variant before publishing.");
        }
        if (productMediaRepository.countByProductId(productId) == 0) {
            throw new BusinessRuleException("product-has-no-media",
                "Add at least one image before publishing.");
        }
        // Re-validated at publish rather than trusted from create time, because the field set is
        // the admin's to change and may have gained a required field since.
        productFieldValueService.replace(product, currentFieldValuesAsRequest(productId));

        product.publish();
        Product stored = productRepository.save(product);
        productEventPublisher.productChanged(stored);
        return withDetail(stored);
    }

    @Transactional
    public ProductResponse archive(long userId, long productId) {
        Product product = requireOwned(userId, productId);
        product.archive();

        Product stored = productRepository.save(product);
        // Archiving takes it off the storefront, so the index has to lose it too - otherwise it
        // stays findable through search while its product page 404s.
        productEventPublisher.productDeleted(stored);
        return withDetail(stored);
    }

    /**
     * Only a draft may be deleted. Once published, a product may have been ordered, and an order's
     * history must keep pointing at something — archiving is what removes it from the storefront.
     */
    @Transactional
    public void delete(long userId, long productId) {
        Product product = requireOwned(userId, productId);

        if (product.getStatus() != ProductStatus.DRAFT) {
            throw new BusinessRuleException("product-not-deletable",
                "Only a draft can be deleted. Archive this product instead.");
        }
        // Emitted before the delete so the row is still readable while the document is built.
        // A draft was never indexed, but sending it is harmless and keeps the rule simple: every
        // disappearance produces an event.
        productEventPublisher.productDeleted(product);
        productRepository.delete(product);
    }

    // --- shared --------------------------------------------------------------------------------

    /**
     * Resolves a product and confirms the caller may write to it.
     *
     * <p>An admin may act on any product; a seller only on their own. A seller asking for someone
     * else's product gets a 404 rather than a 403 — a 403 confirms that the id exists, which is
     * enough to enumerate a competitor's catalogue.
     */
    @Transactional(readOnly = true)
    public Product requireOwned(long userId, long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));

        boolean isAdmin = CurrentUser.hasRole(Roles.ADMIN) || CurrentUser.hasRole(Roles.SUPER_ADMIN);

        if (!isAdmin && !product.isOwnedBy(userId)) {
            throw ResourceNotFoundException.of("Product", productId);
        }
        return product;
    }

    /**
     * Recomputes the denormalised price range and stock total, then re-publishes.
     *
     * <p>Called after any variant or stock change. Those move price and availability, which are
     * exactly what the storefront sorts and filters on - so skipping the event here would leave
     * search offering a product at a price it no longer has, or as in stock when it is not.
     */
    @Transactional
    public void refreshDerivedFields(long productId) {
        productRepository.recomputeDerivedFields(productId);

        // Re-read: the recompute ran as SQL, so the in-memory entity still holds the old figures.
        productRepository.findById(productId).ifPresent(productEventPublisher::productChanged);
    }

    private ProductResponse withDetail(Product product) {
        List<VariantResponse> variants =
            productVariantRepository.findByProductIdOrderByIdAsc(product.getId()).stream()
                .map(VariantResponse::from).toList();
        List<ProductMediaResponse> media =
            productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId()).stream()
                .map(ProductMediaResponse::from).toList();

        return ProductResponse.of(product, variants, media);
    }

    /**
     * Listing shape, with media fetched for the whole page in one query.
     *
     * <p>Done here rather than inside a per-product mapper because a mapper would issue one select
     * per row — a category page of 24 products costing 24 extra queries for thumbnails alone, which
     * is invisible in a test with three products and obvious in production.
     */
    private PageResponse<ProductResponse> summarise(Page<Product> page) {
        List<Long> ids = page.getContent().stream().map(Product::getId).toList();

        Map<Long, List<ProductMediaResponse>> mediaByProduct = ids.isEmpty()
            ? Map.of()
            : productMediaRepository.findByProductIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(ProductMedia::getProductId,
                    Collectors.mapping(ProductMediaResponse::from, Collectors.toList())));

        return PageResponse.of(page.map(product ->
            ProductResponse.summary(product, mediaByProduct.getOrDefault(product.getId(), List.of()))));
    }

    /** The product's stored field values, shaped as a request so publish can re-validate them. */
    private List<ProductRequest.ProductFieldValueRequest> currentFieldValuesAsRequest(long productId) {
        return productFieldValueRepository.findByProductId(productId).stream()
            .collect(Collectors.groupingBy(ProductFieldValue::getFieldId,
                Collectors.mapping(ProductFieldValue::getFieldValueId, Collectors.toList())))
            .entrySet().stream()
            .map(entry -> new ProductRequest.ProductFieldValueRequest(entry.getKey(), entry.getValue()))
            .toList();
    }

    private String resolveSlug(String supplied, String name, Long excludingProductId) {
        String slug = supplied != null && !supplied.isBlank() ? supplied.trim() : Slugs.deriveOrNull(name);

        if (slug == null) {
            throw new BusinessRuleException("slug-required",
                "A slug could not be derived from this product's name, which happens when the name "
                    + "has no Latin letters. Please supply one.");
        }

        productRepository.findBySlug(slug)
            .filter(existing -> !existing.getId().equals(excludingProductId))
            .ifPresent(existing -> {
                throw new ConflictException("product-slug-taken",
                    "A product with slug '" + slug + "' already exists.");
            });

        return slug;
    }
}
