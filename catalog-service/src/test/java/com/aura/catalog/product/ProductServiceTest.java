package com.aura.catalog.product;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryService;
import com.aura.catalog.inventory.StockAvailability;
import com.aura.catalog.product.dto.ProductRequest;
import com.aura.catalog.product.dto.ProductResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Seller ownership and the leaf-category rule.
 *
 * <p>Ownership is the one that has to be right everywhere rather than mostly: a single write path
 * that forgets it lets any seller edit any product, and nothing about that surfaces as an error.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final long SELLER = 7L;
    private static final long OTHER_SELLER = 8L;
    private static final long CATEGORY_ID = 5L;
    private static final long PRODUCT_ID = 100L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductMediaRepository productMediaRepository;

    @Mock
    private ProductFieldValueRepository productFieldValueRepository;

    @Mock
    private ProductFieldValueService productFieldValueService;

    @Mock
    private ProductEventPublisher productEventPublisher;

    @Mock
    private CategoryService categoryService;

    @Mock
    private StockAvailability stockAvailability;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, stockAvailability, productVariantRepository,
            productMediaRepository, productFieldValueRepository, productFieldValueService,
            productEventPublisher, categoryService);
        authenticateAs(SELLER, "SELLER");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long userId, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a", authorities));
    }

    private Product product(long id, long sellerId, ProductStatus status) {
        Product product = Product.draft(sellerId, CATEGORY_ID, "Shirt", "shirt", "A shirt");
        product.setId(id);
        product.setStatus(status);
        return product;
    }

    private ProductRequest request(String name, String slug) {
        return new ProductRequest(CATEGORY_ID, name, slug, "desc", List.of());
    }

    private void echoSave() {
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void noMediaOrVariants() {
        lenient().when(productVariantRepository.findByProductIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of());
        lenient().when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(anyLong()))
            .thenReturn(List.of());
    }

    @Nested
    class Creation {

        @Test
        @DisplayName("a product starts as a draft owned by its creator")
        void createsADraft() {
            noMediaOrVariants();
            when(categoryService.requireLeaf(CATEGORY_ID)).thenReturn(new Category());
            when(productRepository.findBySlug("shirt")).thenReturn(Optional.empty());
            echoSave();

            ProductResponse response = service.create(SELLER, request("Shirt", "shirt"));

            assertThat(response.status()).isEqualTo(ProductStatus.DRAFT);
            assertThat(response.sellerId()).isEqualTo(SELLER);
            assertThat(response.publishedAt()).isNull();
        }

        @Test
        @DisplayName("a non-leaf category is refused before anything is written")
        void nonLeafCategoryRefused() {
            when(categoryService.requireLeaf(CATEGORY_ID))
                .thenThrow(new BusinessRuleException("category-not-leaf", "not a leaf"));

            assertThatThrownBy(() -> service.create(SELLER, request("Shirt", "shirt")))
                .isInstanceOf(BusinessRuleException.class);

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("the slug is derived from the name when omitted")
        void slugDerived() {
            noMediaOrVariants();
            when(categoryService.requireLeaf(CATEGORY_ID)).thenReturn(new Category());
            when(productRepository.findBySlug("blue-cotton-shirt")).thenReturn(Optional.empty());
            echoSave();

            assertThat(service.create(SELLER, request("Blue Cotton Shirt", null)).slug())
                .isEqualTo("blue-cotton-shirt");
        }

        @Test
        @DisplayName("a Persian name with no slug asks for one rather than inventing an identifier")
        void persianNameNeedsSlug() {
            when(categoryService.requireLeaf(CATEGORY_ID)).thenReturn(new Category());

            assertThatThrownBy(() -> service.create(SELLER, request("پیراهن مردانه", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("supply one");
        }

        @Test
        @DisplayName("a duplicate slug is refused")
        void duplicateSlugRefused() {
            when(categoryService.requireLeaf(CATEGORY_ID)).thenReturn(new Category());
            when(productRepository.findBySlug("shirt"))
                .thenReturn(Optional.of(product(999L, OTHER_SELLER, ProductStatus.ACTIVE)));

            assertThatThrownBy(() -> service.create(SELLER, request("Shirt", "shirt")))
                .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Ownership {

        @Test
        @DisplayName("another seller's product is a 404, not a 403")
        void otherSellersProductIsNotFound() {
            // A 403 would confirm the id exists, which is enough to walk the id space and count a
            // competitor's catalogue.
            when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product(PRODUCT_ID, OTHER_SELLER, ProductStatus.ACTIVE)));

            assertThatThrownBy(() -> service.requireOwned(SELLER, PRODUCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a seller may act on their own product")
        void ownProductAllowed() {
            Product own = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(own));

            assertThat(service.requireOwned(SELLER, PRODUCT_ID)).isSameAs(own);
        }

        @Test
        @DisplayName("an admin may act on any seller's product")
        void adminMayActOnAnyProduct() {
            authenticateAs(999L, "ADMIN");
            Product someoneElses = product(PRODUCT_ID, OTHER_SELLER, ProductStatus.ACTIVE);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(someoneElses));

            assertThat(service.requireOwned(999L, PRODUCT_ID)).isSameAs(someoneElses);
        }

        @Test
        @DisplayName("a missing product is a 404")
        void missingProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireOwned(SELLER, PRODUCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Publishing {

        private Product draft;

        @BeforeEach
        void draftExists() {
            draft = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(draft));
        }

        @Test
        @DisplayName("publishing needs at least one active variant")
        void needsAVariant() {
            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(0L);

            assertThatThrownBy(() -> service.publish(SELLER, PRODUCT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("active variant");

            assertThat(draft.getStatus()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("publishing needs at least one image")
        void needsAnImage() {
            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);
            when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(0L);

            assertThatThrownBy(() -> service.publish(SELLER, PRODUCT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("image");
        }

        @Test
        @DisplayName("field values are re-validated at publish, since the admin may have added a required field")
        void revalidatesFieldsAtPublish() {
            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);
            when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
            when(productFieldValueRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
            doThrow(new BusinessRuleException("field-required", "Material is required"))
                .when(productFieldValueService).replace(any(), any());

            assertThatThrownBy(() -> service.publish(SELLER, PRODUCT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("required");

            assertThat(draft.getStatus()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("a complete product goes live and records when")
        void publishesWhenComplete() {
            noMediaOrVariants();
            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);
            when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
            when(productFieldValueRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
            echoSave();

            ProductResponse response = service.publish(SELLER, PRODUCT_ID);

            assertThat(response.status()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(response.publishedAt()).isNotNull();
        }

        @Test
        @DisplayName("republishing keeps the original publication date")
        void republishKeepsOriginalDate() {
            // "New arrivals" should mean when shoppers first saw it, not when it was last toggled.
            noMediaOrVariants();
            draft.publish();
            var firstPublished = draft.getPublishedAt();
            draft.archive();

            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);
            when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
            when(productFieldValueRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
            echoSave();

            assertThat(service.publish(SELLER, PRODUCT_ID).publishedAt()).isEqualTo(firstPublished);
        }
    }

    @Nested
    class Deletion {

        @Test
        @DisplayName("a draft may be deleted")
        void draftDeletable() {
            Product draft = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(draft));

            service.delete(SELLER, PRODUCT_ID);

            verify(productRepository).delete(draft);
        }

        @Test
        @DisplayName("a published product is archived, never deleted — orders still point at it")
        void publishedNotDeletable() {
            when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product(PRODUCT_ID, SELLER, ProductStatus.ACTIVE)));

            assertThatThrownBy(() -> service.delete(SELLER, PRODUCT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Archive");

            verify(productRepository, never()).delete(any());
        }

        @Test
        @DisplayName("an archived product is not deletable either")
        void archivedNotDeletable() {
            when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product(PRODUCT_ID, SELLER, ProductStatus.ARCHIVED)));

            assertThatThrownBy(() -> service.delete(SELLER, PRODUCT_ID))
                .isInstanceOf(BusinessRuleException.class);
        }
    }

    @Nested
    class SearchIndexEvents {

        @Test
        @DisplayName("publishing emits a change, so the product becomes findable")
        void publishEmitsChange() {
            noMediaOrVariants();
            Product draft = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(draft));
            when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);
            when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
            when(productFieldValueRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
            echoSave();

            service.publish(SELLER, PRODUCT_ID);

            verify(productEventPublisher).productChanged(draft);
        }

        @Test
        @DisplayName("archiving emits a deletion, so it stops being findable")
        void archiveEmitsDeletion() {
            // Without this the product stays in the index while its page 404s - findable through
            // search, broken when clicked.
            noMediaOrVariants();
            Product live = product(PRODUCT_ID, SELLER, ProductStatus.ACTIVE);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(live));
            echoSave();

            service.archive(SELLER, PRODUCT_ID);

            verify(productEventPublisher).productDeleted(live);
        }

        @Test
        @DisplayName("deleting a draft emits a deletion too")
        void deleteEmitsDeletion() {
            Product draft = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(draft));

            service.delete(SELLER, PRODUCT_ID);

            verify(productEventPublisher).productDeleted(draft);
        }

        @Test
        @DisplayName("a variant or stock change re-publishes, because price and availability moved")
        void derivedFieldRefreshEmitsChange() {
            Product live = product(PRODUCT_ID, SELLER, ProductStatus.ACTIVE);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(live));

            service.refreshDerivedFields(PRODUCT_ID);

            verify(productRepository).recomputeDerivedFields(PRODUCT_ID);
            verify(productEventPublisher).productChanged(live);
        }
    }

    @Nested
    class StorefrontReads {

        @Test
        @DisplayName("a draft is invisible to shoppers")
        void draftNotVisible() {
            when(productRepository.findBySlug("shirt"))
                .thenReturn(Optional.of(product(PRODUCT_ID, SELLER, ProductStatus.DRAFT)));

            assertThatThrownBy(() -> service.getPublishedBySlug("shirt"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an archived product is invisible to shoppers")
        void archivedNotVisible() {
            when(productRepository.findBySlug("shirt"))
                .thenReturn(Optional.of(product(PRODUCT_ID, SELLER, ProductStatus.ARCHIVED)));

            assertThatThrownBy(() -> service.getPublishedBySlug("shirt"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an active product is served with its variants and media")
        void activeVisible() {
            noMediaOrVariants();
            when(productRepository.findBySlug("shirt"))
                .thenReturn(Optional.of(product(PRODUCT_ID, SELLER, ProductStatus.ACTIVE)));

            assertThat(service.getPublishedBySlug("shirt").status()).isEqualTo(ProductStatus.ACTIVE);
        }
    }

    @Nested
    class Updates {

        @Test
        @DisplayName("changing category re-checks the leaf rule")
        void categoryChangeChecksLeaf() {
            Product existing = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));
            when(categoryService.requireLeaf(9L))
                .thenThrow(new BusinessRuleException("category-not-leaf", "not a leaf"));

            assertThatThrownBy(() -> service.update(SELLER, PRODUCT_ID,
                new ProductRequest(9L, "Shirt", "shirt", null, List.of())))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("field values are re-validated on every update, not only on a category change")
        void fieldsRevalidatedOnEveryUpdate() {
            noMediaOrVariants();
            Product existing = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));
            when(productRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            echoSave();

            service.update(SELLER, PRODUCT_ID, request("Renamed", "renamed"));

            verify(productFieldValueService).replace(eq(existing), any());
        }

        @Test
        @DisplayName("keeping your own slug is not a conflict with yourself")
        void ownSlugNotAConflict() {
            noMediaOrVariants();
            Product existing = product(PRODUCT_ID, SELLER, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));
            when(productRepository.findBySlug("shirt")).thenReturn(Optional.of(existing));
            echoSave();

            assertThat(service.update(SELLER, PRODUCT_ID, request("Shirt", "shirt")).slug())
                .isEqualTo("shirt");
        }
    }
}
