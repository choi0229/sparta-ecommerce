package org.teamsparta.productapi.domain.category.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.teamsparta.productapi.domain.category.dto.CategoryRequest;
import org.teamsparta.productapi.domain.category.dto.CategoryResponse;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.domain.category.repository.CategoryRepository;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        List<Category> categories = categoryRepository.findAllByStatusOrderBySortOrderAscIdAsc(Status.ACTIVE);

        Map<Long, CategoryResponse> categoryResponseMap = new HashMap<>();

        for(Category category : categories){
            CategoryResponse response = CategoryResponse.builder()
                    .id(category.getId())
                    .name(category.getName())
                    .childCategories(new ArrayList<>())
                    .build();
            categoryResponseMap.put(category.getId(), response);
        }

        List<CategoryResponse> rootCategories = new ArrayList<>();
        for(Category category : categories){
            CategoryResponse categoryResponse = categoryResponseMap.get(category.getId());

            if(ObjectUtils.isEmpty(category.getParent())){
                rootCategories.add(categoryResponse);
            }else{
                CategoryResponse parentResponse = categoryResponseMap.get(category.getParent().getId());
                if(parentResponse != null) {
                    parentResponse.childCategories().add(categoryResponse);
                }
            }
        }
        return rootCategories;
    }

    @Transactional(readOnly = true)
    public CategoryResponse findCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .childCategories(category.getChildren().stream()
                        .map(child -> CategoryResponse.builder()
                                .id(child.getId())
                                .name(child.getName())
                                .build())
                        .toList())
                .build();
    }

    @Transactional
    public void create(CategoryRequest request){
        Category parent = null;
        if(request.parentId() != null){
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));
        }

        Category category = Category.builder()
                .name(request.name())
                .parent(parent)
                .status(Status.ACTIVE)
                .sortOrder(request.sortOrder())
                .build();

        categoryRepository.save(category);
    }

    @Transactional
    public void update(Long categoryId, CategoryRequest request){
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));

        Category parent = null;
        if(request.parentId() != null){
            if(request.parentId().equals(categoryId)){
                throw new DomainException(DomainExceptionCode.CATEGORY_SAME_PARENT);
            }
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));
        }

        category.update(request.name(), parent, request.status(), request.sortOrder());
    }

    @Transactional
    public void delete(Long categoryId){
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));

        if(categoryRepository.existsByParent_Id(categoryId)){
            throw new DomainException(DomainExceptionCode.CATEGORY_HAS_CHILDREN);
        }
        category.updateStatus(Status.DELETED);
    }
}
