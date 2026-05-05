---
paths:
  - "product-api/**"
---

# product-api 작업 규칙

`product-api`는 상품, 카테고리, SKU/Variant, 상품 검색, Elasticsearch 인덱스 동기화, 약식 결제 흐름을 담당합니다.

## 핵심 원칙

- 상품 데이터의 Source of Truth는 PostgreSQL입니다.
- Elasticsearch는 검색 성능 개선을 위한 조회용 projection이며 Source of Truth가 아닙니다.
- 상품 정보 변경이 기존 주문 스냅샷을 변경해서는 안 됩니다.
- SKU/Variant는 주문 스냅샷과 재고 연동 가능성을 고려해 수정합니다.
- 기존 도메인 용어를 단순 리팩토링 목적으로 변경하지 않습니다.

---

## 상품 / SKU / Variant 규칙

상품, 카테고리, SKU, Variant 관련 로직을 수정할 때는 다음을 고려합니다.

- 상품 기본 정보
- 상품 옵션
- SKU 식별자 안정성
- Variant 조합
- 가격 변경
- 상품 활성화/비활성화
- 주문 시점 스냅샷에 필요한 데이터
- 재고 서비스와의 연동 가능성

`order-api`의 주문 스냅샷에 필요한 필드를 제거하거나 이름을 변경할 때는 API 응답 계약과 이벤트 계약을 함께 검토합니다.

---

## 약식 결제 흐름 규칙

이 프로젝트의 결제 흐름은 독립 `payment-api`가 아니라 `product-api` 내부에 약식 구현되어 있습니다.

결제 관련 코드를 수정할 때는 다음을 고려합니다.

- 주문 Saga와의 이벤트 계약
- 결제 성공/실패 이벤트 처리
- 결제 실패 시 재고 보상 흐름
- 중복 결제 이벤트 처리
- 향후 독립 결제 서비스로 분리될 가능성

사용자의 명시적인 요청 없이 결제 기능을 별도 `payment-api`로 분리하지 않습니다.

결제 흐름은 약식 구현이므로, 사용자의 명시적인 요청 없이 실제 PG 연동 수준의 복잡한 결제 구조를 추가하지 않습니다.

---

## 결제 이벤트 멱등성 규칙

결제 관련 이벤트는 중복 전달될 수 있으므로 멱등하게 처리합니다.

중복 처리는 다음 식별자를 기준으로 고려합니다.

- `paymentId`
- `eventId`
- `orderId`
- processed event record

중복 결제 성공/실패 이벤트가 주문, 재고, 보상 흐름을 중복 실행하지 않도록 합니다.

---

## Elasticsearch / 검색 규칙

Elasticsearch는 상품 검색 성능 개선을 위한 보조 저장소입니다.

검색 로직을 변경할 때는 다음을 검토합니다.

- Elasticsearch mapping
- 검색 조건
- 정렬 조건
- 페이징 방식
- query latency
- DB fallback 여부
- 인덱스 갱신 시점
- k6 부하 테스트 영향

`LIKE %keyword%` 기반 full scan을 재도입하지 않습니다.

검색 결과가 일시적으로 DB와 다를 수 있는 eventual consistency를 고려합니다.

검색 성능 개선을 주장하려면 측정 결과, 로그, 테스트 결과, 또는 명확한 실행 계획 근거를 제시합니다.

---

## 이벤트 및 동기화 규칙

상품 데이터 변경이 다른 서비스에 영향을 주는 경우 이벤트 계약을 검토합니다.

이벤트를 추가하거나 수정할 때는 다음을 확인합니다.

- 이벤트 payload 필드
- 이벤트 버전 호환성
- Consumer 영향 범위
- Elasticsearch 인덱스 동기화
- 중복 이벤트 처리 가능성
- 재처리 가능성

DB 커밋과 이벤트 발행의 불일치가 발생할 수 있는 흐름에서는 Transactional Outbox 패턴을 우선 고려합니다.

---

## 테스트 규칙

`product-api`의 비즈니스 로직을 수정한 경우 다음 테스트를 고려합니다.

- 상품 생성
- 상품 수정
- 상품 삭제 또는 비활성화
- SKU 생성 및 수정
- Variant 조합 처리
- 상품 검색
- Elasticsearch document 동기화
- 중복 이벤트 처리
- DB와 Elasticsearch 간 eventual consistency 상황

테스트는 가능한 경우 다음 명령어로 실행합니다.

```bash
cd product-api
./gradlew test
```

테스트를 실행하지 못한 경우, 실행하지 못한 이유와 사용자가 실행해야 할 명령어를 명확히 설명합니다.

---

## 배포 반영

배포가 필요한 경우 `deploy-api` Skill을 사용합니다.