package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.domain.Specification;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.global.enums.Status;

public class ProductSpecs {
    public static Specification<Product> statusEq(Status status){
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Product> categoryEq(Long categoryId){
        return (root, query, cb) -> categoryId == null ? null : cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Product> brandLike(String brandName){
        return (root, query, cb) -> (brandName == null || brandName.isBlank()) ? null : cb.like(cb.lower(root.get("brandName")), "%" + brandName.toLowerCase() + "%");
    }

    public static Specification<Product> nameLike(String keyword){
        return (root, query, cb) -> (keyword == null || keyword.isBlank()) ? null : cb.like(cb.lower(root.get("name")), "%" + keyword.toLowerCase() + "%");
    }
}
