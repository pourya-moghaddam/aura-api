package com.aura.catalog.product;

import com.aura.catalog.media.MediaGateway;
import com.aura.catalog.product.dto.ProductMediaRequest;
import com.aura.catalog.product.dto.ProductMediaResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Links a product to files in media-service.
 *
 * <p>Nothing is attached without asking media-service first, and that question does double duty:
 * it confirms the file passed validation and scanning, and — because the check runs as the calling
 * seller against an owner-scoped endpoint — that the file is theirs. Skipping it would let a seller
 * put another seller's photograph, or an unscanned upload, on their own product by supplying its id.
 */
@Service
@RequiredArgsConstructor
public class ProductMediaService {

    private final ProductMediaRepository productMediaRepository;
    private final ProductService productService;
    private final MediaGateway mediaGateway;

    @Transactional(readOnly = true)
    public List<ProductMediaResponse> listFor(long userId, long productId) {
        productService.requireOwned(userId, productId);
        return productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).stream()
            .map(ProductMediaResponse::from).toList();
    }

    @Transactional
    public ProductMediaResponse attach(long userId, long productId, ProductMediaRequest request) {
        productService.requireOwned(userId, productId);

        productMediaRepository.findByProductIdAndMediaId(productId, request.mediaId())
            .ifPresent(existing -> {
                throw new ConflictException("media-already-attached",
                    "That file is already attached to this product.");
            });

        if (!mediaGateway.isUsableBy(request.mediaId())) {
            throw new BusinessRuleException("media-not-usable",
                "That file is not available. It may still be processing, may have failed the "
                    + "security scan, or may not belong to you.");
        }

        // Attaching to a product is what gives the file a public audience, so it is published here
        // rather than at upload time. Before this point the image is the seller's private draft;
        // after it, a shopper with no token has to be able to render it.
        mediaGateway.publish(request.mediaId());

        boolean primary = request.isPrimary() || productMediaRepository.countByProductId(productId) == 0;
        if (primary) {
            // Cleared first: uq_product_media_one_primary is a unique index, so setting the new
            // primary before demoting the old one violates it mid-transaction.
            productMediaRepository.clearPrimary(productId);
        }

        ProductMedia media = ProductMedia.of(
            productId, request.mediaId(), request.kind(), request.sortOrder(), primary);

        return ProductMediaResponse.from(productMediaRepository.save(media));
    }

    @Transactional
    public ProductMediaResponse makePrimary(long userId, long productId, long productMediaId) {
        productService.requireOwned(userId, productId);
        ProductMedia media = requireBelongingTo(productId, productMediaId);

        productMediaRepository.clearPrimary(productId);
        media.setPrimary(true);

        return ProductMediaResponse.from(productMediaRepository.save(media));
    }

    @Transactional
    public ProductMediaResponse reorder(long userId, long productId, long productMediaId, int sortOrder) {
        productService.requireOwned(userId, productId);
        ProductMedia media = requireBelongingTo(productId, productMediaId);

        media.setSortOrder(sortOrder);
        return ProductMediaResponse.from(productMediaRepository.save(media));
    }

    /**
     * Detaches a file from the product. The file itself is left alone — media-service owns it, the
     * seller may be using it elsewhere, and deleting someone's upload because they removed it from
     * one product is not a decision this service gets to make.
     */
    @Transactional
    public void detach(long userId, long productId, long productMediaId) {
        Product product = productService.requireOwned(userId, productId);
        ProductMedia media = requireBelongingTo(productId, productMediaId);

        if (product.getStatus() == ProductStatus.ACTIVE
            && productMediaRepository.countByProductId(productId) <= 1) {
            throw new BusinessRuleException("product-needs-an-image",
                "A published product must keep at least one image.");
        }

        boolean wasPrimary = media.isPrimary();
        productMediaRepository.delete(media);

        // Something has to be the listing image. Promoting the next one keeps that true without
        // asking the seller to notice.
        if (wasPrimary) {
            productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).stream()
                .findFirst()
                .ifPresent(next -> {
                    next.setPrimary(true);
                    productMediaRepository.save(next);
                });
        }
    }

    private ProductMedia requireBelongingTo(long productId, long productMediaId) {
        return productMediaRepository.findById(productMediaId)
            .filter(media -> media.getProductId() == productId)
            .orElseThrow(() -> ResourceNotFoundException.of("Product media", productMediaId));
    }
}
