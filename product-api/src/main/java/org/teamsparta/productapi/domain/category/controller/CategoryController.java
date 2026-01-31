package org.teamsparta.productapi.domain.category.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.productapi.domain.category.dto.CategoryRequest;
import org.teamsparta.productapi.domain.category.dto.CategoryResponse;
import org.teamsparta.productapi.domain.category.service.CategoryService;
import org.teamsparta.productapi.global.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public ApiResponse<Void> create(@RequestBody @Valid CategoryRequest request) {
        categoryService.create(request);
        return ApiResponse.ok();
    }

    @GetMapping
    public ApiResponse<List<CategoryResponse>> findAll() {
        return ApiResponse.ok(categoryService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<CategoryResponse> findById(@PathVariable Long id) {
        CategoryResponse response = categoryService.findCategoryById(id);
        return ApiResponse.ok(response);
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody @Valid CategoryRequest request) {
        categoryService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.ok();
    }
}
