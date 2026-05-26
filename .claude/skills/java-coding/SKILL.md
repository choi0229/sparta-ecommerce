---
name: java-spring-coding
description: Java/Spring Boot 코드 작성, 리팩토링, QueryDSL 검색 조건 작성, DTO 변환, 예외 처리, API 응답 구조 개선 작업에서 사용합니다.
---

# java-spring-coding Skill

이 Skill은 Java/Spring Boot 코드 작성 및 리팩토링 시 사용합니다.

## 기본 원칙

- 기존 프로젝트 패턴을 우선 따릅니다.
- Controller에서 JPA Entity를 직접 노출하지 않습니다.
- 생성자 주입을 사용하고 필드 주입은 피합니다.
- DTO 변환은 간단한 경우 `from()`, `toEntity()`를 사용합니다.
- 복잡한 변환은 Mapper 클래스로 분리합니다.
- QueryDSL 동적 조건은 가능하면 `BooleanExpression` 메서드로 분리합니다.
- 비즈니스 예외는 공통 예외 구조와 ErrorCode를 우선 사용합니다.

## 참고 문서

필요한 경우 아래 문서를 참고합니다.

- `references/querydsl.md`
- `references/dto-mapping.md`
- `references/exception-response.md`
- `references/update-pattern.md`