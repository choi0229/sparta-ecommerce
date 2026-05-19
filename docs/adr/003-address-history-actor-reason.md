# ADR-003: 주소 변경 이력에 변경 주체(actorType)·변경 사유(reason) 저장 여부

- 상태: 보류 (Deferred)
- 결정일: 2026-05-19
- 적용 범위:
  - logistics-api `shipment_address_history` 테이블 (주 대상)
  - address-api `user_address_history` 테이블 (동일 고려사항 적용)

---

## 배경

### 현재 저장 구조

두 테이블 모두 주소 변경의 **무엇(what)**과 **언제(when)**는 기록하지만, **누가(who)**·**왜(why)**는 기록하지 않는다.

**shipment_address_history (logistics-api)**

배송 READY 상태에서 수취인 주소를 수정할 때 이력을 저장한다.

```text
shipment_id
previous_recipient_name, previous_recipient_address
new_recipient_name,       new_recipient_address
changed_at
```

현재 변경 진입점: `PATCH /shipments/{shipmentId}/address` — 인증 없이 shipmentId 기반으로 직접 수정

**user_address_history (address-api)**

사용자 주소록의 생성·수정·삭제 이력을 저장한다. 기본 배송지 자동 해제도 UPDATE 이력으로 포함된다.

```text
address_id, user_id
action_type (CREATE / UPDATE / DELETE)
before_recipient_name / address / is_default
after_recipient_name  / address / is_default
created_at
```

현재 변경 진입점:
- `POST /addresses`, `PATCH /addresses/{id}`, `DELETE /addresses/{id}` — 사용자 직접 요청
- 기본 배송지 신규 지정 시 기존 기본 배송지 자동 해제 — 시스템 트리거

---

### actorType·reason이 유용한 이유

**actorType (변경 주체)** 을 기록하면 다음이 가능해진다.

- CS 상담원이 고객 대신 변경한 이력과 고객이 직접 바꾼 이력을 분리해 책임 소재를 명확히 할 수 있다
- 시스템 자동 변경(기본 배송지 해제, 배치 보정 등)이 사람의 조작과 섞이지 않아 감사 로그의 신뢰도가 높아진다
- "이 배송지 변경을 누가 했나요?"라는 CS 문의에 쿼리 한 번으로 답할 수 있다

**reason (변경 사유)** 을 기록하면 다음이 가능해진다.

- "단순 오타 수정", "CS 요청으로 수정", "시스템 주소 정규화" 등 변경 맥락을 이력에서 직접 파악할 수 있다
- 반복적인 변경 패턴 분석이나 이상 변경 감지가 쉬워진다

---

### 현재 인증 시스템 부재로 인한 한계

- 인증 시스템이 없다. 요청 주체를 식별할 방법이 없다.
- 모든 API 요청이 외부에서 동일하게 들어온다. 고객 직접 변경과 CS 에이전트 대리 변경을 구분하는 식별자가 없다.
- `USER`로 하드코딩하면 CS 에이전트 도입 이후 과거 이력이 오염된다.
- reason 파라미터가 현재 API request에 없다. 클라이언트가 전달할 방법이 없으므로 서버에서 값을 만들 수 없다.

---

## 선택지

### A안: 지금 actorType·reason 컬럼 즉시 추가

Flyway migration으로 두 테이블에 컬럼을 추가한다. actorType은 현재 `UNKNOWN` 또는 `USER`로 채우고, reason은 nullable로 둔다.

### B안: SYSTEM 케이스만 먼저 추가

`user_address_history`의 기본 배송지 자동 해제 케이스만 `actorType=SYSTEM`으로 기록하고, 나머지는 NULL로 둔다.

### C안: 인증/권한 시스템 도입 이후 구현

변경 주체 판별이 가능해지고, reason 수집을 위한 API 계약 변경이 합의된 뒤 한 번에 구현한다.

### D안: 영구 미도입

actorType·reason을 이 두 테이블에 추가하지 않는다. 운영/CS 추적은 기존 필드 조합과 외부 로그로 처리한다.

---

## 선택지 비교

| 기준 | A (즉시 추가) | B (SYSTEM 선행) | C (인증 후) | D (영구 미도입) |
|------|-------------|--------------|-----------|-------------|
| 데이터 품질 | 나쁨 — `UNKNOWN`/`USER` 오염 | 부분적 — NULL 의미 모호 | **좋음** — 처음부터 올바르게 채움 | 해당 없음 |
| 감사/CS 추적성 | 명목상 있음, 신뢰 불가 | SYSTEM 케이스만 | **도입 후 완전** | 기존 필드 조합으로 제한적 |
| 인증 부재 한계 | 극복 불가 — actorType 의미 없음 | SYSTEM만 정확 | 인증 후 완전 해소 | 영향 없음 |
| API 계약 변경 필요 | reason은 필요, 미지정 시 NULL | 불필요 | **필요하지만 계획적으로** | 불필요 |
| 기존 이력 보정 | 불필요 (처음부터 UNKNOWN) | 불필요 | migration 시 UNKNOWN 채우기 | 불필요 |
| 구현 복잡도 | 낮음 (migration만) | 낮음 | 보통 (migration + 로직) | 없음 |
| 이력 신뢰도 영향 | **부정적** — NULL/UNKNOWN 누적 | 부분적 부정적 | **없음** — 오염 없이 시작 | 중립 |
| 포트폴리오 설명 | "컬럼은 있지만 채워지지 않음" | "부분 구현" | **"정책 결정 후 올바르게 도입"** | "범위 밖으로 판단" |

---

## 현재 결정: C안 채택 — 인증/권한 시스템 도입 이후 구현

다음 이유로 **지금은 actorType·reason 필드를 추가하지 않는다.**

**1. 인증 없이는 actorType을 신뢰성 있게 기록할 수 없다.**

현재 모든 API 요청은 인증 없이 처리된다. USER·CS_AGENT를 구분할 식별자가 없으므로, `UNKNOWN` 또는 `USER` 하드코딩 외의 선택지가 없다. CS 에이전트 기능이 도입되면 과거 이력은 영구적으로 "누가 바꿨는지 알 수 없는" 상태로 남는다.

**2. reason은 API 계약 변경 없이 수집할 수 없다.**

현재 배송지 변경·주소 수정 API는 reason 파라미터를 받지 않는다. 서버가 "왜 변경했는지"를 추론할 방법이 없으므로, 컬럼을 추가해도 NULL만 누적된다.

**3. B안(SYSTEM 선행)도 현재는 불필요하다.**

`user_address_history`의 기본 배송지 자동 해제는 이미 `action_type=UPDATE` + `before_is_default=true` + `after_is_default=false` 조합으로 구분 가능하다. 별도 actorType 컬럼 없이 쿼리로 식별되므로 스키마 추가 비용 대비 이익이 없다.

**4. D안(영구 미도입)은 감사/CS 추적성을 포기하는 것이다.**

인증 시스템이 갖춰지면 actorType은 충분히 구현 가능하고, 운영/CS 가치도 명확하다. 도입 시점을 뒤로 미루는 것이지, 영구적으로 불필요한 기능은 아니다.

**5. 의미 없는 NULL/UNKNOWN은 이력 신뢰도를 낮춘다.**

감사 목적 이력에서 "기록 불가"와 "비어 있음"이 같은 NULL로 섞이면, 이후 이력 데이터를 신뢰하기 어려워진다. 처음부터 올바르게 채울 수 있는 시점에 도입하는 것이 데이터 품질상 안전하다.

---

## 후속 구현 기준

아래 조건이 충족되는 시점에 ADR을 개정하고 구현을 진행한다. 세 조건을 동시에 충족할 필요는 없으며, actorType과 reason을 별도 시점에 나눠 구현해도 된다.

| 조건 | 설명 |
|------|------|
| 인증 시스템 도입 | JWT 또는 세션 기반으로 요청 주체 식별 가능 — USER와 CS_AGENT 구분 |
| API 계약 변경 합의 | 배송지 변경·주소 수정 API에 `reason` 파라미터 추가 여부 결정 |
| CS 에이전트 진입점 계획 확정 | CS_AGENT 케이스가 생기는 시점을 기준으로 맞춰 구현 |

---

## 후속 구현 예상 방향

### 스키마 확장

```sql
-- logistics-api
ALTER TABLE shipment_address_history
    ADD COLUMN actor_type VARCHAR(20),   -- USER | CS_AGENT | SYSTEM | UNKNOWN
    ADD COLUMN reason     VARCHAR(255);  -- nullable

-- address-api
ALTER TABLE user_address_history
    ADD COLUMN actor_type VARCHAR(20),
    ADD COLUMN reason     VARCHAR(255);
```

기존 행 처리:
- 인증 도입 전 이력: `actor_type='UNKNOWN'`
- `action_type='UPDATE'` + `before_is_default=true` + `after_is_default=false` 패턴은 migration 시 `actor_type='SYSTEM'`으로 보정 가능

### API 계약 변경 (reason 수집)

```text
PATCH /shipments/{shipmentId}/address
Body: { recipientName, recipientAddress, reason? }

PATCH /addresses/{id}
Body: { recipientName?, recipientAddress?, isDefault?, reason? }
```

reason은 optional. CS 에이전트 도구에서는 필수로 강제할 수 있다.

### 호출자 주입 방식

```text
- SecurityContextHolder 또는 JWT claim에서 actorType 추출
- 시스템 트리거(기본 배송지 자동 해제 등)는 코드 레벨에서 SYSTEM 명시
```

### 관리자 조회 API 확장

```text
GET /admin/addresses/histories?actorType=CS_AGENT
```

---

## 지금 당장 할 것 / 나중에 할 것

### 지금 (이 ADR 작성 시점)
- [x] actorType·reason 저장 정책 ADR 작성 (이 파일)
- [x] README 향후 개선 과제 항목 갱신

### 나중에 (후속 구현 기준 도달 시)
- [ ] 인증 시스템 도입 후 `actorType` 추출 로직 구현
- [ ] API 계약 변경: reason 파라미터 추가 (선택적)
- [ ] Flyway migration: 두 테이블에 `actor_type`, `reason` 컬럼 추가
- [ ] 기존 이력 보정: `UNKNOWN` 채우기 + SYSTEM 케이스 역추론 업데이트
- [ ] 관리자 조회 API에 actorType 필터 추가 (`GET /admin/addresses/histories?actorType=`)
