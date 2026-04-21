# Claude Code 피드백 로그

이 문서는 Claude Code를 활용한 logistics-api 생성 및 하네스 구축 과정에서 실제로 발견되고 보강된 내용을 기록합니다.
재발 방지 수단이 코드·설정에 반영되었는지 추적하는 용도로 유지합니다.

---

## 2026-04-21 — logistics-api 생성 및 하네스 구축

### 1. OutboxPublisherJob self-invocation 문제

**발견 경로**
코드 리뷰 중 `OutboxPublisherJob.publish()` 내부에서 `markSent()` / `markFailed()`를 같은 클래스의 메서드로 직접 호출하고 있음을 확인.

**영향**
Spring AOP는 프록시 기반이므로 같은 Bean 내 자기 호출(self-invocation)은 프록시를 우회합니다.
`markSent` / `markFailed`에 선언된 `@Transactional(propagation = REQUIRES_NEW)`가 무시되어,
outbox 상태 갱신이 `publish()` 트랜잭션에 묶이거나 전혀 트랜잭션 보호를 받지 못하는 상태였습니다.
발행 실패 시 outbox 레코드가 올바르게 FAILED로 전환되지 않아 무한 재시도 또는 유실 위험이 있었습니다.

**수정**
`OutboxEventTransactionalService`를 별도 Spring Bean으로 분리하여 `markSent()` / `markFailed()`를 이관.
`OutboxPublisherJob`은 외부 Bean을 주입받아 호출하므로 AOP 프록시가 정상 적용됩니다.

**재발 방지**
- `.claude/rules/kafka-outbox-saga.md`에 "Outbox 상태 갱신은 self-invocation 주의" 항목 포함
- 코드 리뷰 체크리스트: `REQUIRES_NEW`를 사용하는 메서드가 같은 클래스 내에 있는지 확인

---

### 2. PESSIMISTIC_WRITE 사용 중 @Transactional 누락 문제

**발견 경로**
`OutboxEventTransactionalService` 분리 리팩토링 과정에서 `OutboxPublisherJob.publish()`의 `@Transactional`이 실수로 제거됨.

**영향**
`OutboxQueryRepository.findBatchForPublish()`는 `PESSIMISTIC_WRITE` 잠금을 사용합니다.
활성 트랜잭션이 없으면 JPA가 잠금을 획득하지 못하거나 `TransactionRequiredException`이 발생합니다.
잠금 없이 여러 인스턴스가 동일 outbox 레코드를 중복 발행할 수 있었습니다.

**수정**
`OutboxPublisherJob.publish()`에 `@Transactional`을 복원.
내부에서 호출하는 `markSent` / `markFailed`는 별도 Bean의 `REQUIRES_NEW`로 동작하므로 충돌 없음.

**재발 방지**
- 코드 리뷰 시 `PESSIMISTIC_WRITE`를 사용하는 Repository 메서드의 호출 경로에 `@Transactional`이 있는지 확인
- 향후 OutboxPublisherJob 수정 시 트랜잭션 어노테이션을 제거하지 않도록 주석으로 의도 명시

---

### 3. IdempotencyRecord PENDING stuck 가능성

**발견 경로**
`OrderEventConsumer`가 `IdempotencyRecord`를 PENDING으로 저장한 뒤, 이후 단계에서 예외가 발생하면 PENDING 상태로 영구히 남는 시나리오를 리뷰에서 식별.

**영향**
Consumer 재시작 후 동일 이벤트가 재전달될 때, PENDING 레코드가 존재하면 "이미 처리 중"으로 판단해 처리를 건너뜀.
실제로는 배송 생성이 완료되지 않았으므로 배송 누락이 발생합니다.

**수정**
`LogisticsTransactionalService.createShipmentForOrderEvent()`를 단일 `@Transactional` 메서드로 작성.
4가지 상태를 하나의 트랜잭션 안에서 처리합니다.

| 상태 | 처리 |
|---|---|
| COMPLETED 레코드 존재 | 중복 — 즉시 반환 |
| PENDING + Shipment 존재 | 레코드만 COMPLETED로 전환 (회복) |
| PENDING + Shipment 없음 | 기존 레코드 재사용 후 생성 |
| 레코드 없음 | PENDING 생성 → Shipment 생성 → COMPLETED |

**재발 방지**
- `.claude/rules/logistics-api.md` 멱등성 항목에 PENDING 회복 케이스 명시
- 단위 테스트 5개로 4가지 상태 + 중복 orderId 케이스 커버

---

### 4. createShipmentForOrderEvent 테스트 부족

**발견 경로**
초기 생성 코드에 단위 테스트가 없었음. 리뷰 과정에서 식별.

**영향**
PENDING stuck / 중복 생성 / 회복 경로가 검증되지 않은 채 CI를 통과할 수 있었습니다.

**수정**
`LogisticsTransactionalServiceTest` 작성 (`@ExtendWith(MockitoExtension.class)`, DB 불필요).

| 테스트 | 검증 내용 |
|---|---|
| `alreadyCompleted_returnsNull` | COMPLETED 레코드 → null 반환 |
| `pendingRecord_shipmentExists_recoversAndReturnsExisting` | PENDING + Shipment 존재 → 회복 |
| `pendingRecord_noShipment_reusesRecordAndCreatesShipment` | PENDING + Shipment 없음 → 생성 |
| `noRecord_createsPendingThenShipmentThenCompletes` | 정상 최초 생성 흐름 |
| `pendingRecord_duplicateOrderId_doesNotCreateNewShipment` | 중복 orderId → 새 Shipment 생성 안 함 |

Shipment.id private 필드는 `ReflectionTestUtils.setField()`로 주입 (`Map.of()` NPE 방지).

**재발 방지**
- `.claude/rules/logistics-api.md` 테스트 항목에 4가지 멱등성 케이스 명시
- GitHub Actions `logistics-api-test` job이 PR마다 실행

---

### 5. README의 위험 명령 설명 문구로 인한 guardrails 오탐

**발견 경로**
`scripts/claude-guardrails.sh`를 GitHub Actions에서 실행했을 때 README.md 때문에 job 실패.

**영향**
CI가 항상 실패하므로 guardrails job이 실질적인 보호 역할을 하지 못했습니다.

**원인**
guardrails의 위험 패턴 스캔이 `scripts/claude-guardrails.sh`와 `.github/` 만 제외하고 있었고,
README.md · `.claude/settings.json` 같은 문서·설정 파일은 스캔 대상에 포함되어 있었습니다.

**수정**
스캔 제외 패턴을 다음으로 확장:

```
^(README\.md|scripts/claude-guardrails\.sh|\.claude/settings\.json|\.github/)
```

**재발 방지**
- 새로운 문서·설정 파일에 위험 명령 예시를 추가할 경우 위 제외 패턴에 경로를 함께 추가
- guardrails 오탐 발생 시 제외 패턴 확인을 첫 번째 디버깅 단계로 삼음
