package org.teamsparta.productapi.domain.product.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryResponse;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.global.enums.Status;

import java.util.List;

import static org.teamsparta.productapi.domain.product.entity.QProduct.product;

@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {
    private final JPAQueryFactory queryFactory;

    public Page<Product> searchProducts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ){
        List<Product> content = queryFactory
                .selectFrom(product)
                .leftJoin(product.category).fetchJoin()
                .where(
                        nameLike(keyword),
                        brandLike(brandName),
                        categoryEq(categoryId),
                        statusEq(status)
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        nameLike(keyword),
                        brandLike(brandName),
                        categoryEq(categoryId),
                        statusEq(status)
                );
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private BooleanExpression nameLike(String keyword){
        return StringUtils.hasText(keyword) ? product.name.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression brandLike(String brandName){
        return StringUtils.hasText(brandName) ? product.brandName.containsIgnoreCase(brandName) : null;
    }

    private BooleanExpression categoryEq(Long categoryId){
        return categoryId != null ? product.category.id.eq(categoryId) : null;
    }

    private BooleanExpression statusEq(Status status){
        return status != null ? product.status.eq(status) : null;
    }
}
