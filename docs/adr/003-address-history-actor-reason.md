# ADR-003: 주소 변경 이력에 변경 주체(actorType)·변경 사유(reason) 저장 여부

- 상태: 보류 (Deferred)
- 결정일: 2026-05-19
- 적용 범위:
  - logistics-api `shipment_address_history` 테이블 (주 대상)
  - address-api `user_address_history` 테이블 (동일 고려사항 적용)

---

## 배경

현재 두 테이블에는 주소 변경의 **무엇(what)**과 **언제(when)**는 기록되지만, **누가(who)**·**왜(why)**는 기록되지 않는다.

### shipment_address_history (logistics-api)

배송 중인 주문의 수취인 정보를 변경할 때 저장하는 이력 테이블이다.

```
shipment_id
previous_recipient_name, previous_recipient_address
new_recipient_name,       new_recipient_address
changed_at
```

현재 변경 진입점:
- `PATCH /logistics/{shipmentId}/address` — 외부 HTTP 호출. 인증 없이 shipmentId 기반으로 직접 수정

### user_address_history (address-api)

사용자 주소록의 CREATE/UPDATE/DELETE 이력 테이블이다.

```
address_id, user_id
action_type (CREATE / UPDATE / DELETE)
before_*, after_*
created_at
```

현재 변경 진입점:
- `POST /addresses` — 주소 생성
- `PATCH /addresses/{id}` — 주소 수정
- `DELETE /addresses/{id}` — 주소 삭제 (soft delete)
- 기본 배송지 신규 지정 시 기존 기본 배송지 자동 해제 — **시스템이 변경 주체**인 유일한 경우

---

## 저장 대상 필드 검토

### 변경 주체 (actorType)

누가 변경했는지를 구분하는 필드다.

**후보 값:**

| actorType | 설명 |
|-----------|------|
| `USER` | 고객이 직접 변경 |
| `CS_AGENT` | 고객 센터 상담원이 대신 변경 |
| `SYSTEM` | 시스템이 자동 변경 (예: 기본 배송지 자동 해제) |

**현재 상황의 한계:**
- 인증 시스템이 없다. 요청 주체를 식별할 방법이 없다.
- 모든 API 요청이 `USER`처럼 보이지만, CS 에이전트가 고객 대신 변경하는 경우와 구분할 수 없다.
- `USER`로 하드코딩하면 CS 에이전트 도입 이후 과거 이력이 오염된다 — "이 변경은 고객이 했나, 상담원이 했나"를 구분할 수 없게 된다.
- `UNKNOWN`으로 저장하는 방법도 있으나, 의미 없는 데이터를 채우는 것과 다르지 않다.

**정확히 기록 가능한 경우:**
- `user_address_history`의 기본 배송지 자동 해제 — `SYSTEM`으로 기록할 수 있다. 호출자가 없는 명확한 시스템 트리거다.
- 그 외 모든 경우는 현재 인증 없이 판별 불가.

---

### 변경 사유 (reason)

왜 변경했는지를 기록하는 필드다.

**저장 방식 선택지:**

| 방식 | 장점 | 단점 |
|------|------|------|
| 자유 텍스트 (VARCHAR) | 유연, 다양한 사유 기록 가능 | 검색 어려움, 형식 불일치, 빈 값 처리 필요 |
| 열거형 (ENUM/VARCHAR with constraint) | 구조화, 집계 용이, 강제 일관성 | 사유 유형 선정 필요, 변경 시 migration 필요 |

**현재 상황의 한계:**
- 현재 API 요청에 `reason` 필드가 없다. 클라이언트가 전달할 방법 자체가 없다.
- API 계약 변경 없이는 의미 있는 reason 값을 받을 수 없다.
- 서버에서 reason을 추론하는 것은 어렵다. "주소를 수정했다"는 사실 이상의 맥락을 코드가 알 수 없다.

---

## 선택지

### A안: 지금 actorType + reason 필드 추가

- Flyway migration으로 두 테이블에 컬럼 추가
- actorType은 현재 `UNKNOWN` 또는 `USER`로 채움
- reason은 nullable로 두고 지금은 NULL

**문제점:**
- `UNKNOWN`은 감사 목적으로 의미가 없다
- `USER`로 하드코딩하면 CS 에이전트 도입 이후 오염
- reason은 API 계약 변경 없이 채울 수 없어 NULL이 계속 쌓임
- 의미 없는 데이터를 위한 스키마 비용 발생

---

### B안: actorType은 SYSTEM 케이스만 먼저 추가

`user_address_history`의 자동 해제(기본 배송지 변경 시 기존 default 해제) 케이스만 `actorType=SYSTEM`으로 기록하고, 나머지는 NULL.

- 장점: NULL이 "인증 도입 전 기록 없음"을 명확히 표현. SYSTEM 케이스는 즉시 정확하게 기록 가능
- 단점: 부분 구현 상태가 오래 지속될 경우, NULL의 의미가 모호해짐. 테이블 스키마 추가(Flyway migration) 필요

---

### C안: 인증 시스템 도입 이후 구현

변경 주체 판별은 인증 시스템이 있어야 의미 있다. reason 수집은 API 계약 변경이 필요하다. 두 조건이 갖춰진 뒤 한 번에 구현한다.

- 장점: 데이터 품질 보장. 불필요한 NULL/UNKNOWN 없음. API 계약과 스키마 변경을 한 번에 설계
- 단점: 구현 시점이 인증 도입에 종속. 이전까지 운영/CS 추적성 한계 유지

---

## 현재 결정: C안 채택 — 인증 시스템 도입 후 구현

다음 이유로 **지금은 actorType·reason 필드를 추가하지 않는다.**

1. **인증 없이는 actorType을 정확히 기록할 수 없다.**
   현재 모든 요청은 인증 없이 처리되며, 사용자·상담원·시스템을 구분하는 주체 식별자가 없다. `UNKNOWN` 또는 `USER` 하드코딩은 CS 에이전트 도입 후 이력 오염으로 이어진다.

2. **reason은 API 계약 변경 없이는 수집 불가하다.**
   현재 배송지 변경 API는 reason 파라미터를 받지 않는다. 서버 단에서 reason을 추론할 방법이 없으므로, 컬럼을 추가해도 의미 있는 값을 채울 수 없다.

3. **B안(SYSTEM 케이스 선행 추가)도 현재는 불필요하다.**
   `user_address_history`에서 SYSTEM 케이스(기본 배송지 자동 해제)는 운영상 이미 `action_type=UPDATE` + `before_is_default=true, after_is_default=false` 조합으로 식별 가능하다. 별도 actorType 컬럼 없이도 쿼리로 구분된다. 스키마 추가 비용 대비 지금 당장 얻는 이익이 없다.

4. **의미 없는 NULL/UNKNOWN은 이력의 신뢰도를 낮춘다.**
   감사 목적의 이력에서 "이 필드는 당시 기록 불가"와 "이 필드는 비어 있음"을 구분하지 못하면, 이후 이력을 신뢰하기 어렵다. 데이터를 처음부터 올바르게 채울 수 있는 시점에 도입하는 것이 낫다.

---

## 후속 구현 기준

아래 조건이 모두 충족되면 ADR 개정 및 구현을 진행한다.

| 조건 | 설명 |
|------|------|
| 인증 시스템 도입 | JWT/세션 기반 요청 주체 식별 가능 — USER와 CS_AGENT 구분 |
| API 계약 변경 합의 | 배송지 변경·주소 수정 API에 `reason` 파라미터 추가 결정 |
| CS 에이전트 도구 구현 또는 계획 수립 | CS_AGENT 진입점이 생기는 시점에 맞춰 구현 |

세 조건이 동시에 충족될 필요는 없다. actorType과 reason을 별도 시점에 나눠 구현해도 된다.

---

## 구현 시 최소 설계 (참고용)

### shipment_address_history 스키마 확장

```sql
ALTER TABLE shipment_address_history
    ADD COLUMN actor_type VARCHAR(20),   -- USER | CS_AGENT | SYSTEM | UNKNOWN
    ADD COLUMN reason     VARCHAR(255);  -- nullable, 자유 텍스트 또는 코드값
```

인증 도입 전 마이그레이션 시: 기존 행은 `actor_type='UNKNOWN'`, `reason=NULL`.

### user_address_history 스키마 확장

```sql
ALTER TABLE user_address_history
    ADD COLUMN actor_type VARCHAR(20),
    ADD COLUMN reason     VARCHAR(255);
```

기존 행 기본값: `actor_type='UNKNOWN'`.
단, `action_type='UPDATE'` + `before_is_default=true` + `after_is_default=false` 패턴은 이미 `SYSTEM` 임을 역추론 가능 — 마이그레이션 시 이 조건으로 SYSTEM으로 채울 수 있다.

### API 계약 변경 (reason 수집)

```
PATCH /logistics/{shipmentId}/address
Body: { recipientName, recipientAddress, reason? }

PATCH /addresses/{id}
Body: { recipientName?, recipientAddress?, isDefault?, reason? }
```

reason은 optional. CS 에이전트 도구에서는 필수로 강제할 수 있다.

### 호출자 주입 방식

인증 시스템 도입 후:
- `SecurityContextHolder` 또는 JWT claim에서 `actorType` 추출
- 시스템 트리거(기본 배송지 자동 해제 등)는 코드 레벨에서 `SYSTEM` 명시

---

## 지금 당장 할 것 / 나중에 할 것

### 지금 (이 ADR 작성 시점)
- [x] actorType·reason 저장 정책 ADR 작성 (이 파일)
- [x] README 향후 개선 과제 항목 갱신

### 나중에 (후속 구현 기준 도달 시)
- [ ] 인증 시스템 도입 후 `actorType` 추출 로직 구현
- [ ] API 계약 변경: reason 파라미터 추가 (선택적)
- [ ] Flyway migration: 두 테이블에 `actor_type`, `reason` 컬럼 추가
- [ ] 기존 이력 마이그레이션: `UNKNOWN` 채우기 + SYSTEM 케이스 역추론 업데이트
- [ ] 관리자 조회 API에 actorType 필터 추가 (`GET /admin/addresses/histories?actorType=`)
