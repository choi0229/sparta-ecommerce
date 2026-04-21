---
paths:
  - "logistics-api/**"
---

# logistics-api 작업 규칙

`logistics-api`는 배송 요청 생성, 배송 상태 관리, 배송 상태 조회, 운송장/택배사 정보 관리, 배송 상태 이력 저장, 배송 이벤트 발행, 주문 이벤트 수신을 담당합니다.

## 핵심 원칙

- `logistics-api`는 배송/물류 도메인의 상태만 소유합니다.
- `order-api`, `product-api`, `inventory-api`의 DB에 직접 접근하지 않습니다.
- 주문 정보는 Kafka 이벤트 또는 API 계약을 통해 전달받습니다.
- 배송 상태 변경은 이벤트 발행을 통해 `order-api`에 전달합니다. `logistics-api`가 `order-api`를 직접 호출해서 주문 상태를 변경하지 않습니다.
- 실제 택배사 API 연동은 사용자의 명시적 요청 전까지 구현하지 않습니다.
- 초기 구현은 mock/simulation 기반으로 둡니다.

---

## 배송 상태 규칙

배송 상태는 다음 enum을 기준으로 관리합니다.

- `READY` — 배송 요청 접수
- `SHIPPED` — 발송 완료
- `IN_TRANSIT` — 배송 중
- `DELIVERED` — 배송 완료
- `FAILED` — 배송 중 실패 (분실, 손상, 배송 불가)
- `CANCELED` — 배송 출발 전 취소 (주문 취소 또는 배송 요청 취소)

허용되는 상태 전이는 다음과 같습니다.

- `READY` → `SHIPPED`, `CANCELED`
- `SHIPPED` → `IN_TRANSIT`, `FAILED`, `CANCELED`
- `IN_TRANSIT` → `DELIVERED`, `FAILED`
- `DELIVERED` → 변경 불가
- `FAILED` → 변경 불가
- `CANCELED` → 변경 불가

유효하지 않은 상태 전이 요청은 예외로 처리하며, 조용히 무시하지 않습니다.

---

## 멱등성 규칙

Kafka/Outbox 공통 원칙은 `kafka-outbox-saga.md`를 따릅니다.

logistics-api 고유 기준은 다음과 같습니다.

- 같은 `orderId`로 배송 요청이 중복 생성되지 않도록 합니다.
- `shipmentId`, `trackingNumber`를 기준으로 중복 상태 변경을 방지합니다.
- 중복 이벤트가 중복 배송 생성·상태 변경·이벤트 발행으로 이어지지 않도록 합니다.

---

## Outbox 규칙

Outbox 공통 원칙은 `kafka-outbox-saga.md`를 따릅니다.

logistics-api 고유 기준은 다음과 같습니다.

- 배송 상태 변경과 outbox 저장은 같은 트랜잭션에서 수행합니다.
- 발행 성공 시 outbox 상태를 갱신합니다.

---

## 트랜잭션 규칙

트랜잭션 경계 공통 원칙은 `kafka-outbox-saga.md`를 따릅니다.

logistics-api 고유 기준은 다음과 같습니다.

- 외부 택배사 API 호출은 `@Transactional` 내부에서 수행하지 않습니다.
- 실제 택배사 API 연동은 사용자의 명시적 요청 전까지 mock/simulation 기반으로 둡니다.
- 외부 호출과 DB 저장이 모두 필요한 경우 다음 구조를 우선합니다.
  - `LogisticsService`: 외부 호출, 흐름 제어, 요청 검증
  - `LogisticsTransactionalService`: 짧은 DB 트랜잭션 안에서 배송 상태 저장, outbox 저장

---

## 배송 이력 규칙

현재 배송 상태뿐 아니라 상태 변경 이력을 저장합니다.

배송 이력에는 다음 정보를 고려합니다.

- `shipmentId`
- `status`
- `description`
- `eventId`
- `occurredAt`

상태 변경 이력은 삭제하거나 덮어쓰지 않습니다.

---

## 테스트 규칙

`logistics-api`의 비즈니스 로직을 수정한 경우 다음 테스트를 고려합니다.

- 배송 요청 생성
- 중복 배송 요청 방지
- 배송 상태 조회
- 배송 상태 변경
- 잘못된 상태 전이 방지
- 배송 완료 처리
- 배송 실패 처리
- 배송 이벤트 발행
- 중복 이벤트 멱등성
- Outbox 저장

테스트는 가능한 경우 다음 명령어로 실행합니다.

```bash
cd logistics-api
./gradlew test
```

테스트를 실행하지 못했다면, 실행하지 못한 이유와 사용자가 실행해야 할 명령어를 명확히 설명합니다.

---

## 배포 반영

배포가 필요한 경우 `deploy-api` Skill을 사용합니다.
