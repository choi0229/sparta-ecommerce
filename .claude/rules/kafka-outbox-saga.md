---
paths:
  - "product-api/**"
  - "order-api/**"
  - "inventory-api/**"
  - "logistics-api/**"
---

# Kafka / Outbox / Saga 공통 작업 규칙

이 문서는 Kafka 이벤트, Transactional Outbox, processed_event, Saga 상태 전이와 관련된 공통 규칙을 정의합니다.

## Kafka 이벤트 원칙

Kafka 이벤트는 중복 전달될 수 있으므로 Consumer는 반드시 멱등하게 동작해야 합니다.

이벤트 처리 로직은 다음 상황을 고려합니다.

- 동일 이벤트 중복 수신
- 이벤트 처리 중 실패
- offset commit 전 장애
- Consumer 재시작
- 순서가 어긋난 이벤트
- 보상 이벤트 중복 수신

Kafka가 정확히 한 번만 전달한다고 가정하지 않습니다.

---

## 이벤트 계약 규칙

이벤트 payload를 수정할 때는 Producer와 Consumer를 함께 검토합니다.

이벤트에는 가능한 경우 다음 식별자를 포함합니다.

- eventId
- eventType
- aggregateId
- orderId
- productId
- skuId
- reservationId
- paymentId
- sagaId
- occurredAt

필드명을 변경하거나 제거할 때는 하위 호환성을 고려합니다.

이벤트 계약 변경은 여러 서비스에 영향을 줄 수 있으므로 단일 서비스 내부 리팩토링처럼 처리하지 않습니다.

---

## processed_event 규칙

Consumer는 중복 이벤트 처리를 방지하기 위해 processed_event 또는 이에 준하는 중복 처리 메커니즘을 사용합니다.

기본 흐름은 다음과 같습니다.

1. eventId 또는 고유 식별자 확인
2. 이미 처리된 이벤트인지 확인
3. 미처리 이벤트인 경우 비즈니스 로직 수행
4. 처리 완료 기록 저장
5. 중복 이벤트는 안전하게 무시하거나 기존 결과 반환

processed_event 저장과 비즈니스 상태 변경의 트랜잭션 경계를 신중히 검토합니다.

---

## Transactional Outbox 규칙

DB 상태 변경과 Kafka 이벤트 발행이 함께 필요한 경우 Transactional Outbox 패턴을 우선 고려합니다.

- 도메인 상태 변경과 outbox 저장은 같은 트랜잭션에서 수행합니다.
- Kafka 이벤트는 DB 커밋 이후 outbox publisher가 발행합니다.
- 발행 성공 시 outbox 상태를 갱신합니다.
- 발행 실패 시 재시도 가능해야 합니다.
- outbox event는 추적 가능해야 합니다.
- DB 커밋 전에 Kafka 이벤트를 직접 발행하지 않습니다.

이벤트 발행 실패로 도메인 상태가 유실되어서는 안 됩니다.

Outbox 상태명은 기존 프로젝트의 명칭을 우선합니다.

예시 상태:

- PENDING
- PUBLISHED
- FAILED
- RETRYING

실패한 outbox event를 조용히 무시하지 않습니다.

---

## Saga / 보상 처리 규칙

Saga 상태는 명시적으로 추적되어야 합니다.

Saga 단계를 추가하거나 수정할 때는 다음을 정의합니다.

- 시작 상태
- 진행 중 상태
- 성공 상태
- 실패 상태
- 보상 필요 상태
- 보상 완료 상태

상태 전이는 추적 가능해야 하며, 실패 상태가 조용히 숨겨지면 안 됩니다.

보상 처리는 멱등해야 합니다.

다음 상황을 고려합니다.

- 재고 예약 후 결제 실패
- 재고 예약 후 결제 timeout
- 결제 성공/실패 이벤트 중복 수신
- 재고 해제 이벤트 중복 수신
- 보상 처리 도중 장애
- 보상 이벤트 재처리

보상 이벤트가 중복 실행되어도 재고, 주문, 결제 상태가 손상되지 않아야 합니다.

---

## 트랜잭션 경계 규칙

이벤트 처리 중 외부 호출과 DB 저장이 함께 필요한 경우 트랜잭션 경계를 짧게 유지합니다.

`@Transactional` 내부에서 다음 작업을 피합니다.

- Kafka request-reply 대기
- 다른 서비스 HTTP 호출
- Elasticsearch 호출
- 긴 blocking 작업
- 대용량 데이터 처리

외부 호출, 상태 저장, 이벤트 발행 단계를 분리합니다.

---

## 테스트 규칙

Kafka, Outbox, Saga 관련 코드를 수정한 경우 다음 테스트를 고려합니다.

- 이벤트 정상 처리
- 동일 이벤트 중복 수신
- 이벤트 처리 실패
- Outbox 저장
- Outbox 발행 실패 후 재시도
- Saga 성공/실패 흐름
- 보상 처리
- 보상 중복 실행
- Consumer 재시작 이후 재처리

테스트를 실행하지 못했다면, 실행하지 못한 이유와 사용자가 실행해야 할 명령어를 명확히 설명합니다.