package com.aura.catalog.category;

import com.aura.catalog.category.dto.CategoryRequest;
import com.aura.catalog.category.dto.CategoryResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cycle prevention and path maintenance are named priority test targets in the development plan
 * (§11), because both fail silently: a cycle makes every subtree query either loop or return
 * nonsense, and a half-rewritten path leaves categories whose ancestors disagree about where they
 * are. Neither shows up as an exception at the time the damage is done.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService service;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categoryRepository);
    }

    private Category category(long id, Long parentId, String path, int depth) {
        Category category = Category.of(parentId, "Cat" + id, "cat" + id, depth, 0);
        category.setId(id);
        category.setPath(path);
        return category;
    }

    private CategoryRequest request(Long parentId, String slug) {
        return new CategoryRequest(parentId, "Name", slug, 0, null);
    }

    /** Makes saveAndFlush behave like the database: hand back the entity with an id. */
    private void saveAssigningId(long id) {
        when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(i -> {
            Category c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(id);
            }
            return c;
        });
    }

    @Nested
    class Create {

        @Test
        @DisplayName("a root category's path is its own id")
        void rootPathIsOwnId() {
            when(categoryRepository.existsBySlug("shoes")).thenReturn(false);
            saveAssigningId(7L);

            CategoryResponse response = service.create(request(null, "shoes"));

            assertThat(response.path()).isEqualTo("7");
            assertThat(response.depth()).isZero();
            verify(categoryRepository).assignPath(7L, "7", 0);
        }

        @Test
        @DisplayName("a child's path extends its parent's, and depth follows")
        void childPathExtendsParent() {
            Category parent = category(3L, null, "3", 0);
            when(categoryRepository.existsBySlug("boots")).thenReturn(false);
            when(categoryRepository.findById(3L)).thenReturn(Optional.of(parent));
            saveAssigningId(9L);

            CategoryResponse response = service.create(request(3L, "boots"));

            assertThat(response.path()).isEqualTo("3.9");
            assertThat(response.depth()).isEqualTo(1);
            verify(categoryRepository).assignPath(9L, "3.9", 1);
        }

        @Test
        @DisplayName("the path is written in a second statement, because it contains the new id")
        void pathNeedsTheGeneratedIdSoItIsWrittenAfterTheInsert() {
            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            saveAssigningId(4L);

            service.create(request(null, "x"));

            // If this ever becomes a single insert, the path would have to be guessed before the
            // sequence hands out the id - which is exactly the bug this ordering avoids.
            InOrderVerification.saveThenAssignPath(categoryRepository);
        }

        @Test
        @DisplayName("a duplicate slug is refused, since slugs are public permanent URLs")
        void duplicateSlugRejected() {
            when(categoryRepository.existsBySlug("taken")).thenReturn(true);

            assertThatThrownBy(() -> service.create(request(null, "taken")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("taken");

            verify(categoryRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("nesting deeper than the limit is refused")
        void tooDeepRejected() {
            Category deep = category(1L, null, "1.2.3.4.5.6", 6);
            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(deep));

            assertThatThrownBy(() -> service.create(request(1L, "deeper")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("6 levels");
        }

        @Test
        @DisplayName("an unknown parent is a 404, not a silent root category")
        void unknownParentRejected() {
            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request(99L, "orphan")))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Reparenting {

        @Test
        @DisplayName("a category cannot be moved beneath its own descendant")
        void cycleRejected() {
            Category clothing = category(1L, null, "1", 0);
            Category shirts = category(3L, 2L, "1.2.3", 2);

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(clothing));
            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(1L, request(3L, "clothing")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("beneath itself");

            verify(categoryRepository, never()).rewriteSubtreePaths(anyString(), anyString());
        }

        @Test
        @DisplayName("a category cannot be moved beneath itself")
        void selfParentRejected() {
            Category clothing = category(1L, null, "1", 0);

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(clothing));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(1L, request(1L, "clothing")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("beneath itself");
        }

        @Test
        @DisplayName("a sibling with a path that merely starts with the same digits is not a descendant")
        void prefixLookalikeIsNotADescendant() {
            // "1.2" is not an ancestor of "1.25" even though the string starts the same way. The
            // check has to compare whole labels, or moving a category under an unrelated sibling
            // gets refused for no reason the admin can see.
            Category moving = category(2L, 1L, "1.2", 1);
            Category lookalike = category(25L, 1L, "1.25", 1);

            when(categoryRepository.findById(2L)).thenReturn(Optional.of(moving));
            when(categoryRepository.findById(25L)).thenReturn(Optional.of(lookalike));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findSubtree("1.2")).thenReturn(List.of(moving));
            when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(i -> i.getArgument(0));
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(2L, request(25L, "moving"));

            verify(categoryRepository).rewriteSubtreePaths("1.2", "1.25.2");
        }

        @Test
        @DisplayName("moving a category rewrites its whole subtree in one statement")
        void subtreePathsRewritten() {
            Category shirts = category(3L, 2L, "1.2.3", 2);
            Category newParent = category(5L, null, "5", 0);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findById(5L)).thenReturn(Optional.of(newParent));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findSubtree("1.2.3")).thenReturn(List.of(shirts));
            when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(i -> i.getArgument(0));
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(3L, request(5L, "shirts"));

            assertThat(shirts.getPath()).isEqualTo("5.3");
            assertThat(shirts.getDepth()).isEqualTo(1);
            verify(categoryRepository).rewriteSubtreePaths("1.2.3", "5.3");
        }

        @Test
        @DisplayName("moving to the root gives a path of just the id")
        void movingToRoot() {
            Category shirts = category(3L, 2L, "1.2.3", 2);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findSubtree("1.2.3")).thenReturn(List.of(shirts));
            when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(i -> i.getArgument(0));
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(3L, request(null, "shirts"));

            assertThat(shirts.getPath()).isEqualTo("3");
            assertThat(shirts.getDepth()).isZero();
            verify(categoryRepository).rewriteSubtreePaths("1.2.3", "3");
        }

        @Test
        @DisplayName("a move is refused when it would push a deep descendant past the limit")
        void deepestDescendantIsWhatCounts() {
            // The moved node itself would land at depth 4, comfortably inside the limit. Its
            // deepest descendant sits 3 levels below it and would land at 7. Checking only the
            // moved node - the obvious implementation - lets this through.
            Category moving = category(3L, 1L, "1.3", 1);
            Category deepest = category(4L, 3L, "1.3.4.9.11", 4);
            Category newParent = category(8L, null, "5.6.7.8", 3);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(moving));
            when(categoryRepository.findById(8L)).thenReturn(Optional.of(newParent));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findSubtree("1.3")).thenReturn(List.of(moving, deepest));

            assertThatThrownBy(() -> service.update(3L, request(8L, "moving")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("more than 6");

            verify(categoryRepository, never()).rewriteSubtreePaths(anyString(), anyString());
        }

        @Test
        @DisplayName("a move that lands the deepest descendant exactly on the limit is allowed")
        void exactlyAtTheLimitIsAllowed() {
            // Guards the boundary from both sides: off-by-one here would either reject legitimate
            // trees or admit ones the breadcrumb UI cannot render.
            Category moving = category(3L, 1L, "1.3", 1);
            Category deepest = category(4L, 3L, "1.3.4.9", 3);
            Category newParent = category(8L, null, "5.6.7.8", 3);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(moving));
            when(categoryRepository.findById(8L)).thenReturn(Optional.of(newParent));
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findSubtree("1.3")).thenReturn(List.of(moving, deepest));
            when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(i -> i.getArgument(0));
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(3L, request(8L, "moving"));

            verify(categoryRepository).rewriteSubtreePaths("1.3", "5.6.7.8.3");
        }

        @Test
        @DisplayName("renaming never touches paths")
        void renameLeavesPathsAlone() {
            Category shirts = category(3L, 2L, "1.2.3", 2);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findBySlug("renamed")).thenReturn(Optional.empty());
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(3L, new CategoryRequest(2L, "Renamed", "renamed", 5, null));

            assertThat(shirts.getPath()).isEqualTo("1.2.3");
            assertThat(shirts.getSortOrder()).isEqualTo(5);
            verify(categoryRepository, never()).rewriteSubtreePaths(anyString(), anyString());
        }

        @Test
        @DisplayName("taking another category's slug is refused")
        void slugConflictOnUpdate() {
            Category shirts = category(3L, 2L, "1.2.3", 2);
            Category other = category(9L, null, "9", 0);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findBySlug("theirs")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.update(3L, request(2L, "theirs")))
                .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("keeping your own slug is not a conflict with yourself")
        void ownSlugIsNotAConflict() {
            Category shirts = category(3L, 2L, "1.2.3", 2);

            when(categoryRepository.findById(3L)).thenReturn(Optional.of(shirts));
            when(categoryRepository.findBySlug("mine")).thenReturn(Optional.of(shirts));
            when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            service.update(3L, request(2L, "mine"));

            verify(categoryRepository).save(shirts);
        }
    }

    @Nested
    class Deletion {

        @Test
        @DisplayName("a category with children is refused with a readable message")
        void withChildrenRefused() {
            Category parent = category(1L, null, "1", 0);
            parent.setChildCount(2);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("subcategories");

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a leaf is deleted")
        void leafDeleted() {
            Category leaf = category(1L, null, "1", 0);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(leaf));

            service.delete(1L);

            verify(categoryRepository).delete(leaf);
        }
    }

    @Nested
    class ProductAttachment {

        @Test
        @DisplayName("a branch category cannot take products")
        void nonLeafRefused() {
            Category branch = category(1L, null, "1", 0);
            branch.setChildCount(3);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(branch));

            assertThatThrownBy(() -> service.requireLeaf(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no subcategories");
        }

        @Test
        @DisplayName("an inactive leaf cannot take products")
        void inactiveLeafRefused() {
            Category leaf = category(1L, null, "1", 0);
            leaf.setActive(false);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(leaf));

            assertThatThrownBy(() -> service.requireLeaf(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not currently accepting");
        }

        @Test
        @DisplayName("an active leaf is accepted")
        void activeLeafAccepted() {
            Category leaf = category(1L, null, "1", 0);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(leaf));

            assertThat(service.requireLeaf(1L)).isSameAs(leaf);
        }
    }

    @Nested
    class Reads {

        @Test
        @DisplayName("the tree nests children under their parents and sorts them")
        void treeIsNested() {
            Category root = category(1L, null, "1", 0);
            root.setSortOrder(0);
            Category childB = category(3L, 1L, "1.3", 1);
            childB.setSortOrder(2);
            Category childA = category(2L, 1L, "1.2", 1);
            childA.setSortOrder(1);
            when(categoryRepository.findAll()).thenReturn(List.of(root, childB, childA));

            List<CategoryResponse> tree = service.listTree();

            assertThat(tree).hasSize(1);
            assertThat(tree.getFirst().children()).extracting(CategoryResponse::id)
                .containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("an unknown slug is a 404")
        void unknownSlug() {
            when(categoryRepository.findBySlug("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBySlug("nope"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("subtree ids come from the path query, not a recursive walk")
        void subtreeIds() {
            Category root = category(1L, null, "1", 0);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(root));
            when(categoryRepository.findSubtree("1"))
                .thenReturn(List.of(root, category(2L, 1L, "1.2", 1)));

            assertThat(service.subtreeIds(1L)).containsExactly(1L, 2L);
        }
    }

    /** Kept out of the test bodies so the ordering assertion reads as one line where it is used. */
    private static final class InOrderVerification {
        static void saveThenAssignPath(CategoryRepository repository) {
            org.mockito.InOrder inOrder = inOrder(repository);
            inOrder.verify(repository).saveAndFlush(any(Category.class));
            inOrder.verify(repository).assignPath(anyLong(), anyString(), anyInt());
        }
    }
}
