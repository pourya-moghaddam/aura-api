package com.aura.catalog.product;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryRepository;
import com.aura.catalog.product.dto.CreateProductRequest;
import com.aura.catalog.product.dto.ProductResponse;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        Product product = Product.builder()
            .category(category)
            .name(request.name())
            .slug(request.slug())
            .description(request.description())
            .attributes(request.attributes())
            .build();

        Product saved = productRepository.save(product);
        return mapToProductResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        return mapToProductResponse(product);
    }

    private ProductResponse mapToProductResponse(Product product) {
        return new ProductResponse(
            product.getId(),
            product.getCategory().getId(),
            product.getCategory().getName(),
            product.getName(),
            product.getSlug(),
            product.getDescription(),
            product.isActive(),
            product.getAttributes()
        );
    }
}
