# ADR-004: 배송 상태 이력과 배송지 변경 이력의 분리/통합 조회 정책

- 상태: 보류 (Deferred)
- 결정일: 2026-05-19
- 적용 범위:
  - logistics-api `shipment_status_history` 테이블
  - logistics-api `shipment_address_history` 테이블

---

## 배경

### 현재 두 이력의 역할

**shipment_status_history**

배송 상태 전이를 추적하는 이력 테이블이다.

```text
shipment_id
status           (READY | SHIPPED | IN_TRANSIT | DELIVERED | FAILED | CANCELED)
description
event_id         (Kafka 이벤트 식별자)
occurred_at
```

- Kafka 이벤트(`OrderCreatedEvent`, 상태 변경 커맨드) 기반으로 상태가 변경된다.
- `event_id`로 어떤 이벤트가 트리거했는지 추적 가능하다.
- 배송 진행 단계 파악, 장애/지연 원인 분석, 상태 전이 감사에 사용된다.

**shipment_address_history**

배송지 수정 이력을 추적하는 테이블이다.

```text
shipment_id
previous_recipient_name,    previous_recipient_address
new_recipient_name,         new_recipient_address
changed_at
```

- `PATCH /shipments/{shipmentId}/address` API 직접 호출로만 생성된다.
- 배송지 정정 경위 추적, CS 문의 대응, 오입력 이력 확인에 사용된다.

---

### 왜 통합 조회 요구가 생기는가

CS 상담 시 특정 배송건에서 다음 질문이 동시에 발생한다.

- "이 배송은 언제 SHIPPED로 넘어갔나?"
- "주소가 언제 바뀌었나? 상태 변경 전인가, 후인가?"
- "주소 변경 이후에도 정상 배송 진행이 됐나?"

두 이력은 모두 `shipment_id`로 연결된다. 시간 순서로 함께 보면 인과관계를 한 번에 파악할 수 있다. 현재는 두 API를 별도 호출해 결과를 수동으로 조합해야 한다.

---

### 두 이력이 의미상 다른 이유

같은 `shipment_id`를 공유하지만 성격이 다르다.

| 구분 | shipment_status_history | shipment_address_history |
|------|------------------------|--------------------------|
| 의미 | 배송 진행 상태 — "지금 어디 있나" | 배송 목적지 정보 — "어디로 보냈나" |
| 변경 원인 | Kafka 이벤트 (비동기) | REST API 직접 호출 (동기) |
| 시간 필드 | `occurred_at` | `changed_at` |
| 고유 필드 | `status`, `description`, `event_id` | `previous_*`, `new_*` recipient |
| 저장 주체 | 상태 전이 로직 | 배송지 수정 API |

응답 모델을 통합하려면 이벤트 타입(STATUS_CHANGED / ADDRESS_CHANGED)에 따라 서로 다른 필드 집합을 갖는 union 구조가 필요하다.

---

## 선택지

### A안: 분리 조회 유지 (현재 상태)

현재처럼 두 이력을 별도 API로 조회한다. 통합 조회는 클라이언트나 운영 도구에서 직접 수행한다.

### B안: 통합 타임라인 API 즉시 구현

`GET /admin/shipments/{shipmentId}/timeline` API를 지금 바로 구현한다. 두 이력을 `occurredAt`/`changedAt` 기준으로 병합해 단일 응답으로 반환한다.

### C안: 저장은 분리 유지, 통합 timeline API를 후속 구현

저장 구조는 바꾸지 않는다. 현재는 구현하지 않되, CS/운영 요구가 구체화되면 read-only projection 기반 통합 조회 API를 별도로 추가한다.

### D안: 통합 조회 미도입

통합 API를 구현하지 않는다. 운영 문서/runbook에서 두 API 호출 방법을 안내하고, 조합은 운영자가 직접 수행한다.

---

## 선택지 비교

| 기준 | A (분리 유지) | B (즉시 구현) | C (후속 구현) | D (미도입) |
|------|------------|------------|------------|---------|
| 도메인 의미 명확성 | **좋음** — 각 이력 독립적 | 보통 — union DTO 필요 | **좋음** — 저장 분리 유지 | **좋음** |
| 운영/CS 조회 편의성 | 나쁨 — 두 API 수동 조합 | **좋음** — 단일 호출 | **좋음** (구현 후) | 나쁨 |
| API 응답 모델 복잡도 | 낮음 | **높음** — union DTO, eventType 필드 | 낮음 (현재), 보통 (구현 후) | 낮음 |
| 정렬 기준 단순성 | 각 테이블 기준 | 복잡 — `occurred_at` vs `changed_at` 통일 필요 | 설계 시 확정 | 각 테이블 기준 |
| 필터링 요구사항 | 단순 | eventType/날짜 범위 등 추가 정의 필요 | 설계 시 확정 | 단순 |
| 기존 API 영향도 | **없음** | 없음 (신규 추가) | **없음** | **없음** |
| 구현 복잡도 | 없음 | **높음** — 응답 스키마, 권한, 페이징, 병합 로직 | 없음 (현재) | 없음 |
| 포트폴리오 설명 | "도메인 경계 의식적 분리" | "구현했지만 스키마 설계 미완" | **"정책 결정 후 단계적 구현"** | "범위 외 판단" |

---

## 현재 결정: C안 채택 — 저장 분리 유지, 통합 timeline API 후속 구현

다음 이유로 **지금은 통합 조회 API를 구현하지 않는다.**

**1. 두 이력은 의미가 달라 저장 통합은 부적절하다.**

`shipment_status_history`는 Kafka 이벤트 기반 상태 전이, `shipment_address_history`는 REST API 기반 데이터 정정이다. 저장 목적과 변경 원인이 다르므로 테이블 통합은 도메인 경계를 흐린다.

**2. 통합 조회는 CS/운영 편의성 측면에서 가치가 있다.**

배송지 변경과 상태 변경의 인과관계를 한 눈에 파악하는 것은 CS 응대 효율에 직접 영향을 준다. D안(미도입)처럼 영구적으로 포기할 기능이 아니다.

**3. 지금 바로 구현하면 범위가 과도해진다.**

B안 구현을 위해서는 다음이 필요하다.

- `occurred_at` vs `changed_at` 필드 이름 통일 또는 추상화
- `eventType: STATUS_CHANGED | ADDRESS_CHANGED` union 응답 DTO 설계
- 관리자 접근 통제 방식 확정 (현재 `X-Admin-Api-Key` 임시 가드 사용 중)
- 페이징, 날짜 범위 필터, 이벤트 타입 필터 정책 정의

현재 단계에서 이 결정들을 한꺼번에 내리는 것은 범위가 크다.

**4. A안(계속 분리)은 충분하지 않다.**

CS 요구가 있을 경우 수동 조합은 운영 부담이 된다. 단순히 현 상태를 유지하는 것은 장기적으로 적절하지 않다.

---

## 후속 구현 기준

아래 조건 중 하나라도 충족되면 ADR을 개정하고 구현을 진행한다.

| 조건 | 설명 |
|------|------|
| CS/운영 화면 요구 | shipment 단위 전체 변경 타임라인을 단일 화면에서 봐야 하는 요구가 구체화될 때 |
| 반복적 CS 대응 사례 | 상태 변경과 주소 변경을 같은 시간축에서 봐야 하는 문의가 반복될 때 |
| 관리자 권한 체계 정립 | X-Admin-Api-Key 임시 가드가 Spring Security 기반으로 전환될 때 |
| 응답 스키마 합의 | `eventType`, union DTO 필드 구조, 정렬/필터 기준이 확정될 때 |

---

## 후속 구현 예상 방향

### 조회 API

```text
GET /admin/shipments/{shipmentId}/timeline
```

### 응답 구조 (예시)

```text
{
  "shipmentId": 1,
  "events": [
    {
      "eventType": "STATUS_CHANGED",
      "occurredAt": "...",
      "status": "SHIPPED",
      "description": "...",
      "eventId": "..."
    },
    {
      "eventType": "ADDRESS_CHANGED",
      "occurredAt": "...",
      "previousRecipientName": "...",
      "previousRecipientAddress": "...",
      "newRecipientName": "...",
      "newRecipientAddress": "..."
    }
  ]
}
```

`shipment_address_history.changed_at`은 `occurredAt`으로 정규화해 단일 시간 기준으로 정렬한다.

### 구현 원칙

```text
- 저장 테이블은 통합하지 않는다
- read-only projection/service에서 두 이력을 shipmentId 기준으로 병합
- occurredAt DESC 또는 ASC 정렬 (클라이언트 파라미터로 선택 가능)
- 관리자 전용 엔드포인트 — X-Admin-Api-Key 또는 Spring Security 관리자 권한
```

---

## 지금 당장 할 것 / 나중에 할 것

### 지금 (이 ADR 작성 시점)
- [x] 분리/통합 조회 정책 ADR 작성 (이 파일)
- [x] README 향후 개선 과제 항목 갱신

### 나중에 (후속 구현 기준 도달 시)
- [ ] timeline 응답 스키마 설계 (`eventType`, union DTO 필드, 페이징/필터 정책)
- [ ] 관리자 접근 통제 방식 확정 (X-Admin-Api-Key 또는 Spring Security)
- [ ] `GET /admin/shipments/{shipmentId}/timeline` API 구현 (read-only projection)
- [ ] `shipment_address_history.changed_at` → `occurredAt` 정규화 처리
- [ ] 단위 테스트 및 통합 테스트
