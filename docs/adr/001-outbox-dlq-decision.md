# ADR-001: logistics-api Outbox DLQ 도입 여부

- 상태: 수락 (Accepted)
- 결정일: 2026-05-05
- 적용 범위: logistics-api outbox_event 실패 처리

---

## 배경

logistics-api의 Transactional Outbox 파이프라인은 현재 다음 레이어로 구성되어 있다.

```
PENDING
  └─ publisher claim → PROCESSING (next_retry_at = now+2min)
       ├─ Kafka send 성공  → SENT
       ├─ Kafka send 실패  → PENDING (retry, 최대 5회) → FAILED
       └─ stale(만료)      → PENDING (StaleOutboxRecoveryJob, 60초 주기, 최대 100건/cycle)

FAILED
  └─ admin API로 수동 PENDING 전환 가능
       POST /admin/outbox/{id}/retry
       POST /admin/outbox/retry?limit=20
```

운영 관측성:
- Prometheus Gauge: `logistics.outbox.events{status=PENDING/PROCESSING/SENT/FAILED}`
- Counter: `logistics.outbox.publish{result=sent/failed}`
- Counter: `logistics.outbox.stale.recovered`, `logistics.outbox.stale.high_retry`
- Counter: `logistics.outbox.admin.retry{type=single/batch}`
- admin API: `GET /admin/outbox?status=FAILED`

---

## 결정: DLQ 미도입 — 수동 재처리 + 모니터링 유지

### 판단 기준별 시나리오 분석

| 실패 원인 | 현재 처리 | admin retry 효과 | DLQ 필요성 |
|---|---|---|---|
| 일시 Kafka 장애 | retry backoff → 복구 | 불필요 | 없음 |
| Kafka 장기 중단 | FAILED 후 admin retry 시 복구 | 있음 | 없음 |
| publisher 프로세스 재시작 | stale recovery → PENDING | 불필요 | 없음 |
| 알 수 없는 eventType | 즉시 throw → 5회 후 FAILED | **없음** (재처리해도 동일 실패) | 잠재적 필요 |
| Kafka topic 미존재 | kafkaSend fail → 5회 후 FAILED | **없음** (인프라 수정 필요) | 잠재적 필요 |
| 잘못된 payload | Kafka는 string 수신하므로 publish 측 문제 아님 | 해당 없음 | 없음 |

### DLQ 미도입 근거

1. **FAILED 상태가 DB-level DLQ 역할을 이미 수행한다.**
   - FAILED 이벤트는 publisher가 건너뛰어 메인 파이프라인을 차단하지 않는다.
   - `GET /admin/outbox?status=FAILED`로 즉시 조회·식별 가능하다.

2. **이벤트 종류가 한정적이다.**
   - `shipment-created-event`, `shipment-status-changed-event` 두 종류만 존재한다.
   - broken row가 대량으로 발생할 구조가 아니다.

3. **현재 메트릭으로 이상 감지 가능하다.**
   - `logistics.outbox.stale.high_retry` counter: 지속 실패 행 수 감지
   - `logistics.outbox.events{status="FAILED"}` gauge: FAILED 누적 추적
   - Prometheus alert rule로 임계치 경보 구성 가능 (별도 구성)

4. **DLQ 도입 시 운영 복잡도가 올라간다.**
   - DB DLQ 테이블: 스키마 추가 + Flyway migration + admin API 추가
   - Kafka DLQ topic: consumer 추가 + 재주입 메커니즘 + 메시지 순서 보장 검토 필요
   - 현재 프로젝트 규모 대비 운영 비용이 크다.

---

## DLQ 도입을 재검토할 기준점

아래 조건 중 하나라도 충족되면 ADR 재검토를 권장한다.

- FAILED row 일평균 100건 이상으로 수동 트리아지가 병목이 되는 경우
- 자동 알림(Slack, PagerDuty 등) 연동이 필요해지는 경우
- "영구 실패(broken row)"와 "재시도 가능 실패"를 코드 레벨에서 구분해야 하는 경우
- 여러 서비스가 동일 Outbox 패턴을 공유하며 DLQ를 중앙화해야 하는 경우

---

## 만약 B를 선택한다면 (최소 설계)

현재 도입하지 않지만, 필요 시 최소 범위 설계안은 다음과 같다.

### 옵션 1: DB 기반 DLQ 테이블 (권장)

```sql
CREATE TABLE outbox_dlq (
    id           BIGSERIAL PRIMARY KEY,
    source_id    BIGINT NOT NULL,        -- outbox_event.id
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    failure_reason TEXT,
    retry_count    INT NOT NULL,
    moved_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);
```

전이 시점: `markFailed()` 내부에서 `retryCount >= maxRetry` 도달 시 DLQ row 삽입 (같은 트랜잭션).

admin 흐름:
- `GET /admin/outbox/dlq` — DLQ 목록 조회
- `POST /admin/outbox/dlq/{id}/reinject` — DLQ row → PENDING 재주입
- `POST /admin/outbox/dlq/{id}/resolve` — 해결 처리 (resolved_at 기록)

기존 코드 영향:
- `OutboxEventTransactionalService.markFailed()` 내부에 DLQ insert 추가
- 새 Flyway migration
- `AdminOutboxController`에 DLQ 엔드포인트 추가

### 옵션 2: Kafka DLQ topic (비권장 — 재주입 복잡도 높음)

- `logistics-dlq` topic 생성
- `markFailed()` 또는 publisher에서 terminal FAILED 시 DLQ topic produce
- 재주입 시 DLQ consumer에서 다시 PENDING insert
- 메시지 순서, 중복, outbox 보장 검토 필요 → DB 기반보다 복잡

---

## 현재 채택한 보완 조치

DLQ 미도입 대신, terminal FAILED 전이 시 명시적 WARN 로그를 추가한다.

```
WARN [OutboxTerminal] id=1 eventType=shipment-created-event aggregateId=SHIP-001 retryCount=5
```

이 로그가 발생하면 운영자는 다음 흐름으로 대응한다.

1. `GET /admin/outbox?status=FAILED` 로 이벤트 확인
2. 원인 분석 (Kafka 연결, eventType 오류, topic 미존재 등)
3. 원인 해결 후 `POST /admin/outbox/{id}/retry` 또는 배치 재처리

---

## 지금 당장 할 것 / 나중에 할 것

### 지금 (이 ADR 작성 시점)
- [x] ADR 문서화 (이 파일)
- [x] `markFailed()` — terminal FAILED 전이 시 WARN 로그 추가
- [ ] Prometheus alert rule: `logistics_outbox_events{status="FAILED"} > 10` (배포 환경 구성 시)

### 나중에 (DLQ 재검토 기준점 도달 시)
- [ ] DB 기반 DLQ 테이블 및 Flyway migration
- [ ] DLQ 전이 로직 (`markFailed()` 확장)
- [ ] DLQ admin API (`/admin/outbox/dlq`)
- [ ] Slack/PagerDuty 알림 연동
