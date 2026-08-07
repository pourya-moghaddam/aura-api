package com.aura.catalog.category;

import com.aura.catalog.category.dto.CategoryResponse;
import com.aura.catalog.category.dto.CreateCategoryRequest;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                .orElseThrow(() -> new ResourceNotFoundException("Parent category not found"));
        }

        Category category = Category.builder()
            .name(request.name())
            .slug(request.slug())
            .parent(parent)
            .build();

        Category saved = categoryRepository.save(category);
        return new CategoryResponse(saved.getId(), saved.getName(), saved.getSlug(),
            saved.getParent() != null ? saved.getParent().getId() : null);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
            .map(c -> new CategoryResponse(c.getId(), c.getName(), c.getSlug(),
                c.getParent() != null ? c.getParent().getId() : null))
            .toList();
    }
}
