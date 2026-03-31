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
import org.teamsparta.productapi.domain.product.entity.ProductPlain;
import org.teamsparta.productapi.global.enums.Status;

import java.util.List;

import static org.teamsparta.productapi.domain.product.entity.QProductPlain.productPlain;

@Repository
@RequiredArgsConstructor
public class ProductPlainQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<ProductPlain> searchProducts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ) {
        List<ProductPlain> content = queryFactory
                .selectFrom(productPlain)
                .leftJoin(productPlain.category).fetchJoin()
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
                .select(productPlain.count())
                .from(productPlain)
                .where(
                        nameLike(keyword),
                        brandLike(brandName),
                        categoryEq(categoryId),
                        statusEq(status)
                );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private BooleanExpression nameLike(String keyword) {
        return StringUtils.hasText(keyword) ? productPlain.name.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression brandLike(String brandName) {
        return StringUtils.hasText(brandName) ? productPlain.brandName.containsIgnoreCase(brandName) : null;
    }

    private BooleanExpression categoryEq(Long categoryId) {
        return categoryId != null ? productPlain.category.id.eq(categoryId) : null;
    }

    private BooleanExpression statusEq(Status status) {
        return status != null ? productPlain.status.eq(status) : null;
    }
}