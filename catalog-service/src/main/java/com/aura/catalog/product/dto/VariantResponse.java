package com.aura.catalog.product.dto;

import com.aura.catalog.product.ProductVariant;

public record VariantResponse(
    Long id,
    Long productId,
    Long colorId,
    Long sizeId,
    String sku,
    Long price,
    Long compareAtPrice,
    boolean isActive
) {

    public static VariantResponse from(ProductVariant variant) {
        return new VariantResponse(
            variant.getId(), variant.getProductId(), variant.getColorId(), variant.getSizeId(),
            variant.getSku(), variant.getPrice(), variant.getCompareAtPrice(), variant.isActive());
    }
}
