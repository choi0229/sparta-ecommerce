# QueryDSL 작성 규칙

## 기본 원칙

동적 검색 조건은 QueryDSL을 사용합니다.

간단한 조건은 `BooleanBuilder`를 사용할 수 있지만, 조건이 2개 이상이거나 재사용 가능성이 있으면 `BooleanExpression` 메서드 분리 방식을 우선합니다.

`where()`는 null 조건을 무시할 수 있으므로, 조건 메서드에서 값이 없으면 `null`을 반환하는 방식을 사용합니다.

---

## 권장 방식: BooleanExpression 메서드 분리

```java
private BooleanExpression keywordContains(String keyword) {
    if (!StringUtils.hasText(keyword)) {
        return null;
    }

    return product.name.containsIgnoreCase(keyword)
            .or(product.description.containsIgnoreCase(keyword));
}

private BooleanExpression categoryEq(Long categoryId) {
    if (categoryId == null) {
        return null;
    }

    return product.category.id.eq(categoryId);
}

private BooleanExpression priceGoe(Integer minPrice) {
    if (minPrice == null) {
        return null;
    }

    return product.price.goe(minPrice);
}

private BooleanExpression priceLoe(Integer maxPrice) {
    if (maxPrice == null) {
        return null;
    }

    return product.price.loe(maxPrice);
}
```

사용 예시는 다음과 같습니다.

```java
List<Product> content = queryFactory
        .selectFrom(product)
        .where(
                keywordContains(condition.getKeyword()),
                categoryEq(condition.getCategoryId()),
                priceGoe(condition.getMinPrice()),
                priceLoe(condition.getMaxPrice())
        )
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
```

---

## 지양하는 방식

조건이 많아질 때 `BooleanBuilder` 안에 모든 조건을 몰아넣지 않습니다.

```java
BooleanBuilder builder = new BooleanBuilder();

if (keyword != null) {
    builder.and(product.name.containsIgnoreCase(keyword));
}

if (categoryId != null) {
    builder.and(product.category.id.eq(categoryId));
}
```

`BooleanBuilder` 자체가 금지는 아니지만, 조건이 많아지면 가독성과 테스트 용이성이 떨어질 수 있습니다.

---

## 정리

- 단순 조건 1~2개: `BooleanBuilder` 허용
- 조건이 많거나 검색 API가 복잡함: `BooleanExpression` 메서드 분리 권장
- 검색 성능이 중요한 경우: QueryDSL보다 Elasticsearch 사용 여부를 먼저 검토
- `LIKE %keyword%` 기반 검색이 병목이면 DB 검색으로 억지 해결하지 않습니다