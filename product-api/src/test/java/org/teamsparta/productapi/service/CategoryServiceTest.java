package org.teamsparta.productapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.productapi.domain.category.dto.CategoryRequest;
import org.teamsparta.productapi.domain.category.dto.CategoryResponse;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.domain.category.repository.CategoryRepository;
import org.teamsparta.productapi.domain.category.service.CategoryService;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class CategoryServiceTest {

    @InjectMocks
    private CategoryService categoryService;

    @Mock
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("전체 카테고리 조회 - 트리 구조 확인")
    void findAll_buildTree(){
        // given
        Category root = Category.builder()
                .name("root")
                .parent(null)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        Category child1 = Category.builder()
                .name("child1")
                .parent(root)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        Category child2 = Category.builder()
                .name("child2")
                .parent(root)
                .status(Status.ACTIVE)
                .sortOrder(2)
                .build();

        Category root2 = Category.builder()
                .name("root2")
                .parent(null)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        ReflectionTestUtils.setField(root, "id", 1L);
        ReflectionTestUtils.setField(child1, "id", 2L);
        ReflectionTestUtils.setField(child2, "id", 3L);
        ReflectionTestUtils.setField(root2, "id", 4L);

        given(categoryRepository.findAllByStatusOrderBySortOrderAscIdAsc(Status.ACTIVE))
                .willReturn(List.of(root, root2, child1, child2));

        // when
        List<CategoryResponse> result = categoryService.findAll();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).childCategories()).hasSize(2);
        assertThat(result.get(0).childCategories().get(0).id()).isEqualTo(2L);
        assertThat(result.get(0).childCategories().get(1).id()).isEqualTo(3L);
        assertThat(result.get(1).id()).isEqualTo(4L);

        then(categoryRepository).should(times(1))
                .findAllByStatusOrderBySortOrderAscIdAsc(Status.ACTIVE);
    }

    @Test
    @DisplayName("특정 카테고리 조회 - 자신+자식 반환")
    void findCategoryById_success(){
        // given
        Category parent = Category.builder()
                .name("parent")
                .parent(null)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        Category child1 = Category.builder()
                .name("child1")
                .parent(parent)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        Category child2 = Category.builder()
                .name("child2")
                .parent(parent)
                .status(Status.ACTIVE)
                .sortOrder(1)
                .build();

        ReflectionTestUtils.setField(parent, "id", 1L);
        ReflectionTestUtils.setField(child1, "id", 2L);
        ReflectionTestUtils.setField(child2, "id", 3L);
        ReflectionTestUtils.setField(parent, "children", List.of(child1, child2));

        given(categoryRepository.findById(1L)).willReturn(Optional.of(parent));

        // when
        CategoryResponse result = categoryService.findCategoryById(1L);

        // then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.childCategories()).hasSize(2);
        assertThat(result.childCategories().stream().map(CategoryResponse::id))
                .containsExactlyInAnyOrder(2L, 3L);

        then(categoryRepository).should(times(1))
                .findById(1L);
    }

    @Test
    @DisplayName("카테고리 생성 - 부모 카테고리가 있는 경우")
    void create_withParent_success(){
        // given
        Category parent = Category.builder()
                .name("parent")
                .status(Status.ACTIVE)
                .sortOrder(null)
                .build();
        ReflectionTestUtils.setField(parent, "id", 1L);

        CategoryRequest request = new CategoryRequest("child", 1L, 1, Status.ACTIVE);
        given(categoryRepository.findById(1L)).willReturn(Optional.of(parent));

        // when
        categoryService.create(request);

        ArgumentCaptor<Category> categoryCaptor = ArgumentCaptor.forClass(Category.class);

        // then
        then(categoryRepository).should().save(categoryCaptor.capture());
        then(categoryRepository).should(times(1)).findById(1L);

        Category savedCategory = categoryCaptor.getValue();
        assertThat(savedCategory).isNotNull();
        assertThat(savedCategory.getName()).isEqualTo("child");
        assertThat(savedCategory.getParent().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("카테고리 생성 실패 - 부모 카테고리가 없으면 NOT_FOUND_CATEGORY")
    void create_fail_parentNotFound(){
        // given
        CategoryRequest request = new CategoryRequest("child", 1L, 1, Status.ACTIVE);
        given(categoryRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        DomainException exception = assertThrows(DomainException.class, () ->
                categoryService.create(request));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.NOT_FOUND_CATEGORY.getMessage());
        then(categoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("카테고리 수정 실패 - 자기 자신을 부모로 설정할 수 없음")
    void update_fail_selfParenting() {
        // given
        Long targetId = 1L;
        Category category = Category.builder()
                .name("category").parent(null).status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", targetId);

        CategoryRequest request = new CategoryRequest("child", targetId, 1, Status.ACTIVE);
        ReflectionTestUtils.setField(category, "id", 2L);

        given(categoryRepository.findById(targetId)).willReturn(Optional.of(category));

        // when & then
        DomainException exception = assertThrows(DomainException.class, () ->
                categoryService.update(targetId, request));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.DUPLICATE_PARENT.getMessage());
    }

    @Test
    @DisplayName("카테고리 삭제 - soft delete 성공")
    void delete_success(){
        // given
        Long categoryId = 1L;
        Category category = Category.builder()
                .name("category").parent(null).status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", categoryId);

        given(categoryRepository.findById(categoryId)).willReturn(Optional.of(category));
        given(categoryRepository.existsByParentId(categoryId)).willReturn(false);

        // when
        categoryService.delete(categoryId);

        // then
        assertThat(category.getStatus()).isEqualTo(Status.DELETED);
        then(categoryRepository).should(times(1)).existsByParentId(categoryId);
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 하위 카테고리가 존재하는 경우")
    void delete_fail_hasChildren() {
        // given
        Long categoryId = 1L;
        Category category = Category.builder()
                .name("category").parent(null).status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", categoryId);

        given(categoryRepository.findById(categoryId)).willReturn(Optional.of(category));
        given(categoryRepository.existsByParentId(categoryId)).willReturn(true);

        // when & then
        DomainException exception = assertThrows(DomainException.class, () ->
                categoryService.delete(categoryId));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.CATEGORY_HAS_CHILDREN.getMessage());
    }
}
