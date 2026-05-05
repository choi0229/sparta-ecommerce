---
name: create-logistics-api
description: 기존 sparta-ecommerce MSA 프로젝트에 신규 logistics-api 서비스를 추가할 때 사용합니다. 사용자가 logistics-api 생성, 배송 서비스 구현, 배송 도메인 추가를 요청한 경우 사용합니다.
---

# create-logistics-api Skill

이 Skill은 sparta-ecommerce MSA 프로젝트에 `logistics-api` 서비스를 신규 추가할 때 사용합니다.

실제 코드 생성 전에 다음 항목을 사용자와 먼저 확인합니다.

- Gradle 멀티모듈 vs 독립 서비스 디렉터리 중 어떤 구조로 추가할지
- Kafka topic 이름과 이벤트 타입 (예: `order.created`, `shipment.status.changed`)
- logistics-api 데이터베이스 설정 (기존 공유 DB vs 독립 DB)
- Kubernetes namespace와 deployment 이름 규칙

---

## logistics-api 목표

- 배송 요청 생성
- 배송 상태 관리
- 배송 상태 조회
- 배송 상태 이력 저장
- 배송 이벤트 발행
- 주문 이벤트 수신

---

## 반드시 지킬 원칙

- `order-api`, `product-api`, `inventory-api`의 DB에 직접 접근하지 않습니다.
- 주문 정보는 Kafka 이벤트 또는 API 계약을 통해 전달받습니다.
- 배송 상태 변경은 이벤트 발행을 통해 `order-api`에 전달합니다.
- `order-api`를 직접 호출해서 주문 상태를 변경하지 않습니다.
- Kafka 이벤트 중복 전달을 고려해 `processed_event` 기반 멱등성을 둡니다.
- 배송 상태 변경과 이벤트 발행이 함께 필요한 경우 Transactional Outbox 패턴을 고려합니다.
- `@Transactional` 내부에서 외부 택배사 API 호출이나 Kafka request-reply 대기를 수행하지 않습니다.
- 실제 택배사 API 연동은 하지 않고 mock/simulation 기반으로 시작합니다.

---

## 기본 패키지 구조

```
logistics-api/
└── src/main/java/.../logistics/
    ├── controller/
    ├── service/
    ├── repository/
    ├── entity/
    ├── dto/
    ├── event/
    ├── consumer/
    ├── producer/
    └── exception/
```

기존 `order-api`, `inventory-api`의 패키지 구조와 명명 규칙을 우선 따릅니다.

---

## 우선 생성할 기능 범위

### 엔티티 및 도메인

- `Shipment` Entity — 배송 기본 정보, `ShipmentStatus`, `orderId`, `trackingNumber`
- `ShipmentStatus` enum
  - `READY` — 배송 준비
  - `SHIPPED` — 출고 완료
  - `IN_TRANSIT` — 배송 중
  - `DELIVERED` — 배송 완료
  - `FAILED` — 배송 중 실패 (분실, 손상, 배송 불가)
  - `CANCELED` — 배송 출발 전 취소 (주문 취소 또는 배송 요청 취소)
- 허용되는 상태 전이
  - `READY` → `SHIPPED`, `CANCELED`
  - `SHIPPED` → `IN_TRANSIT`, `FAILED`, `CANCELED`
  - `IN_TRANSIT` → `DELIVERED`, `FAILED`
  - `DELIVERED`, `FAILED`, `CANCELED` → 변경 불가
  - 유효하지 않은 전이 요청은 예외로 처리하며 조용히 무시하지 않음
- `ShipmentStatusHistory` Entity — 상태 변경 이력, `shipmentId`, `status`, `eventId`, `occurredAt`
  - 상태 변경 시 새로운 이력 row를 추가하며, 기존 이력은 삭제하거나 덮어쓰지 않음
- `ProcessedEvent` Entity — Kafka 이벤트 중복 처리 방지
- `OutboxEvent` Entity 또는 기존 프로젝트 패턴에 맞는 outbox 구조

### API

- 배송 요청 생성 API — `POST /shipments`
- 배송 상태 조회 API — `GET /shipments/{shipmentId}`
- 배송 상태 변경 API — `PATCH /shipments/{shipmentId}/status`

### 서비스 계층 분리

- `LogisticsService` — 외부 호출, 이벤트 흐름 제어, 요청 검증
- `LogisticsTransactionalService` — 짧은 DB 트랜잭션 안에서 배송 상태 저장, 이력 저장, outbox 저장
- `@Transactional` 내부에서 외부 택배사 API 호출, `order-api` 호출, Kafka request-reply 대기를 하지 않음

### 이벤트

- 주문 이벤트 Consumer 초안 — 주문 생성 이벤트 수신, 배송 요청 생성 트리거
- 배송 이벤트 Outbox 초안 — 배송 상태 변경 이벤트 발행

### 테스트

- 배송 요청 생성 기본 테스트
- 중복 배송 요청 방지 테스트
- 잘못된 상태 전이 방지 테스트
- 중복 이벤트 멱등성 테스트
- Outbox 저장 테스트

---

## 하지 말아야 할 것

- `payment-api`를 새로 만들지 않습니다. 결제 흐름은 `product-api` 내 약식 구현으로 유지합니다.
- 실제 택배사 API(CJ대한통운, 우체국 등)를 연동하지 않습니다.
- `order-api`, `product-api`, `inventory-api` DB에 직접 접근하지 않습니다.
- 기존 서비스의 코드 구조를 대규모 리팩토링하지 않습니다.
- 기존 서비스의 이벤트 계약을 임의로 변경하지 않습니다.

---

## 생성 후 추가로 수정할 파일

`logistics-api` 코드 생성이 완료된 후 다음 파일을 추가로 수정합니다.

- `scripts/redeploy-api.sh` — `logistics-api` 지원 서비스 추가
- `.claude/skills/deploy-api/SKILL.md` — 지원 서비스 목록에 `logistics-api` 추가
- `CLAUDE.md` — 프로젝트 개요에 `logistics-api` 서비스 설명 추가

`kafka-outbox-saga.md` paths는 이미 `logistics-api/**`를 포함하고 있으므로 수정하지 않아도 됩니다.

- `performance-observability.md` frontmatter — 기본적으로 인프라/성능 작업 경로(`loadtest/**`, `prometheus/**` 등)만 포함합니다. 일반 비즈니스 로직 작업 시 성능/관측 규칙이 자동 로드되지 않도록 `logistics-api/**`는 기본적으로 추가하지 않습니다. logistics-api 성능 튜닝이 필요한 시점에 사용자가 직접 추가 여부를 결정합니다.
