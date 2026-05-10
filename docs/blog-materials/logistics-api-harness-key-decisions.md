# logistics-api + Claude Code 하네스 핵심 의사결정

작업 과정에서 방향을 결정한 주요 선택지와 판단 근거를 정리했습니다.

---

## 1. 기존 서비스 코드를 건드리지 않고 logistics-api 독립 추가

**선택지**
- A: logistics-api를 완전히 독립된 서비스로 추가, 기존 서비스 무수정
- B: 공통 모듈 추출 또는 기존 서비스 일부 리팩토링 후 추가

**선택: A**

**근거**
MSA의 핵심은 서비스 독립성입니다.
신규 서비스 추가가 기존 서비스를 수정하는 이유가 되어서는 안 됩니다.
product-api, order-api, inventory-api는 이미 운영 중인 서비스이므로 변경 범위를 최소화하고,
logistics-api는 자신의 도메인 데이터만 소유하며 독립 배포 가능한 구조로 추가했습니다.

**결과**
기존 3개 서비스의 코드, DB 스키마, Kafka 토픽 계약이 모두 그대로 유지되었습니다.

---

## 2. order-api 이벤트 계약을 변경하지 않고 logistics-api가 맞추는 Option A 선택

**배경**
order-api는 `order-create-event`를 발행하지만, 페이로드에 배송지 정보(`recipientName`, `recipientAddress`)가 없습니다.

**선택지**
- Option A: logistics-api가 기존 `order-create-event` 구조에 맞춤 (배송지는 null 허용)
- Option B: order-api의 이벤트 구조를 확장하여 배송지 정보 추가

**선택: Option A**

**근거**
이벤트 계약 변경은 Producer(order-api)와 Consumer(logistics-api) 양쪽을 동시에 수정해야 합니다.
order-api DTO, Entity, Flyway migration, 이벤트 페이로드까지 변경 범위가 넓어집니다.
1차 MVP의 목표는 "기존 주문 흐름을 깨지 않고 배송 서비스를 연결하는 것"이었으므로,
logistics-api가 현재 이벤트 구조에 적응하는 최소 변경 방향을 선택했습니다.

**결과**
order-api 코드 변경 없이 logistics-api가 `order-create-event`를 수신해 배송 생성 트리거가 동작합니다.

---

## 3. 배송지 정보 null 허용 — MVP 우선, 이후 보강

**배경**
실제 배송에는 수령인 이름과 주소가 필요하지만, 현재 order-api 이벤트에는 이 정보가 없습니다.

**선택**
MVP에서는 `recipientName`, `recipientAddress`를 null 허용으로 처리합니다.

**이후 보강 방향 (미결)**
- 주문 이벤트 확장: order-api 페이로드에 배송지 추가
- 별도 배송지 업데이트 API: `PATCH /shipments/{id}/address`
- 사용자 주소 서비스 연동

---

## 4. Transactional Outbox 패턴 유지 — DB 커밋과 이벤트 발행의 원자성 보장

**선택지**
- A: 배송 상태 변경 후 Kafka에 직접 발행
- B: Outbox 테이블에 저장 후 스케줄러가 발행 (Transactional Outbox 패턴)

**선택: B**

**근거**
Kafka 발행은 DB 커밋과 달리 실패 가능성이 있습니다.
DB 커밋 성공 → Kafka 발행 실패 시나리오에서 이벤트가 유실됩니다.
Outbox 패턴은 DB 트랜잭션 안에서 이벤트를 저장하고, 스케줄러가 별도로 발행하므로
발행 실패 시 재시도가 가능하고 이벤트 유실을 방지합니다.
기존 product-api, order-api, inventory-api도 모두 Outbox 패턴을 사용하고 있어 일관성을 유지했습니다.

---

## 5. IdempotencyRecord PENDING stuck 방지 — 단일 트랜잭션 구조

**문제**
초기 구현은 다음과 같이 단계를 분리했습니다:

```
1. IdempotencyRecord PENDING 저장
2. Shipment 생성
3. IdempotencyRecord COMPLETED 업데이트
```

2단계에서 예외가 발생하면 PENDING 상태로 영구히 남습니다.
Consumer 재시작 후 동일 이벤트가 재전달될 때 "이미 처리 중"으로 오판해 배송 누락이 발생합니다.

**해결**
모든 단계를 단일 `@Transactional` 메서드로 통합하고, 4가지 상태를 명시적으로 처리합니다.

| 입력 상태 | 처리 |
|---|---|
| COMPLETED 레코드 존재 | 중복 이벤트 → 즉시 반환 |
| PENDING + Shipment 존재 | PENDING stuck 회복 → 레코드만 COMPLETED 전환 |
| PENDING + Shipment 없음 | 이전 시도 부분 실패 → 재사용 후 생성 |
| 레코드 없음 | 최초 처리 → 정상 흐름 |

트랜잭션이 롤백되면 IdempotencyRecord도 함께 롤백되므로 PENDING stuck이 발생하지 않습니다.

---

## 6. CI Gate는 1차로 logistics-api만 검증

**선택지**
- A: 모든 서비스(product-api, order-api, inventory-api, logistics-api) CI 연결
- B: 이번 작업 범위인 logistics-api만 CI 연결, 나머지는 추후 확장

**선택: B**

**근거**
CI pipeline을 전체 서비스로 한 번에 확장하면 각 서비스의 테스트 환경(DB, Kafka 등)을 모두 구성해야 하며,
이번 작업의 목표는 Claude Code 하네스 검증에 초점이 있었습니다.
logistics-api는 `@ExtendWith(MockitoExtension.class)` 기반 단위 테스트만 있어 외부 의존성 없이 CI 실행이 가능합니다.
단계적 확장을 위해 먼저 logistics-api만 연결하고, 이후 다른 서비스를 추가하는 방향으로 설계했습니다.

---

## 7. Claude Code 하네스 — 단순 프롬프트 대신 구조적 제어

**문제 인식**
Claude Code를 단순히 "코드 생성 도구"로 사용하면:
- 매번 프로젝트 규칙을 자연어로 설명해야 함
- 세션마다 규칙 해석이 달라질 수 있음
- 기존 서비스 코드를 의도치 않게 수정할 수 있음
- 배포 절차가 일관되지 않을 수 있음

**선택: 하네스 구조 먼저 구성 후 코드 생성**

| 하네스 구성 요소 | 역할 |
|---|---|
| `CLAUDE.md` | 전체 프로젝트 핵심 원칙 |
| `.claude/rules/*.md` | 서비스별/주제별 작업 규칙 |
| `.claude/skills/` | 반복 작업 절차 재사용 |
| `.claude/settings.json` | 위험 명령 실행 차단/승인 |
| `scripts/claude-guardrails.sh` | 커밋 전 안전 검사 |
| `.github/workflows/claude-ci-gate.yml` | PR마다 자동 검증 |

**결과**
logistics-api 생성 전 과정에서 기존 3개 서비스 코드가 변경되지 않았습니다.
Claude Code가 rules를 기반으로 도메인 경계, 트랜잭션 규칙, 멱등성 원칙을 일관되게 적용했습니다.

---

## 9. revfactory/harness를 그대로 이식하지 않고 agents 레이어만 점진 도입

> 기존 하네스와 고도화 후 하네스의 전체 비교는 `logistics-api-harness-before-after.md`를 참조하세요.

**배경**
하네스를 고도화하는 방법으로 revfactory/harness 구조 전체를 이식하는 방안을 검토했습니다.

**선택지**
- A: revfactory/harness 전체 구조 이식 (orchestrator Skill, 다중 agents, 자동화 파이프라인 포함)
- B: 현재 프로젝트에 맞는 `.claude/agents/` 레이어만 점진적으로 추가

**선택: B**

**근거**
현재 프로젝트는 이미 `CLAUDE.md`, `rules/`, `skills/`, `settings.json`, `guardrails.sh`, CI Gate까지 구성된 상태입니다.
전체를 한 번에 이식하면 기존 하네스 구조와 충돌하거나 과도하게 복잡해질 수 있습니다.
역할 분리(설계·구현·리뷰·검증)가 가장 즉각적인 효과를 내므로, agents 레이어만 먼저 추가하고 orchestrator Skill은 이후 단계로 미뤘습니다.

**결과**
`.claude/agents/` 4개 파일(msa-architect, backend-builder, code-reviewer, qa)로 역할이 명시적으로 분리되었고,
기존 rules, skills, settings 구조와의 충돌 없이 통합되었습니다.

---

## 10. Agents를 P0/P1로 나누어 단계적으로 추가

**배경**
4개 agents를 한 번에 추가하는 것과 역할별로 단계를 나누는 것 중 선택이 필요했습니다.

**선택지**
- A: 4개 agents(msa-architect, backend-builder, code-reviewer, qa)를 한 번에 생성
- B: P0(설계·구현 분리) 먼저, P1(리뷰·검증 분리) 이후 추가

**선택: B**

**근거**
P0 agents(msa-architect, backend-builder)는 코드 생성 흐름의 핵심 분기점이므로 먼저 검증이 필요합니다.
P0 agents를 실제 작업(런타임 로그 개선)에 적용해 동작을 확인한 뒤 P1을 추가하면,
각 단계의 효과를 개별적으로 평가할 수 있습니다.
orchestrator Skill(`msa-change-orchestrator`)은 4개 agents의 동작이 안정화된 이후로 미뤘습니다.
실제로 P0/P1 agents를 실제 작업에 적용하고 안정화를 확인한 뒤 `msa-change-orchestrator` Skill을 추가했습니다.

**결과**
P0 agents로 런타임 로그 개선 작업을 수행한 뒤 P1 agents를 추가하는 흐름으로 진행했고,
각 단계에서 역할 분리의 효과를 구체적으로 확인할 수 있었습니다.

---

## 11. 테스트 보강을 P0/P1 단위로 나누어 진행

**배경**
logistics-api에 단위 테스트가 부족한 상태에서 테스트 보강 범위와 순서를 결정해야 했습니다.

**선택지**
- A: 전체 테스트를 한 번에 작성
- B: 엔티티/서비스 계층(P0) 먼저, Consumer/Scheduler 계층(P1) 이후 추가

**선택: B**

**근거**
엔티티 레벨 테스트(`OutboxEventTest`)는 외부 의존성이 전혀 없어 가장 빠르게 작성하고 검증할 수 있습니다.
서비스 레벨(`OutboxEventTransactionalServiceTest`)은 Repository mock만 필요해 두 번째 단계로 적합합니다.
Consumer(`OrderEventConsumerTest`)와 Scheduler(`OutboxPublisherJobTest`)는 JSON 파싱, Kafka mock 등 추가 고려 사항이 있어 마지막에 배치했습니다.
작은 단위로 나누면 실패 시 원인 범위가 좁고, 진행 중 피드백을 반영하기 쉽습니다.

**결과**
P0 4개 테스트 → P1 OrderEventConsumerTest → P1 OutboxPublisherJobTest 순서로 추가했으며,
각 단계에서 테스트 설계 패턴(mock 전략, 비교 방식)이 다음 단계에 자연스럽게 재사용되었습니다.

---

## 12. OutboxPublisherJob 폴링 주기 유지 — 로그 설정만 개선

**배경**
Outbox 폴링 주기 500ms로 인해 분당 ~120줄 SQL 로그가 발생하는 문제가 있었습니다.

**선택지**
- A: 폴링 주기를 늘려 SQL 발생 빈도를 줄임
- B: 폴링 주기는 유지하고 로그 설정만 개선

**선택: B**

**근거**
Outbox 폴링 주기는 배송 이벤트 발행 지연 시간에 직접 영향을 줍니다.
500ms를 늘리면 Kafka 발행 지연이 늘어나 다운스트림 서비스(order-api 등)의 상태 반영이 느려집니다.
문제의 실제 원인은 폴링 빈도가 아니라 `show-sql: true` 설정이므로, 로그 설정만 수정하는 것이 올바른 해결책입니다.

**결과**
폴링 주기 500ms를 유지하면서 `show-sql: false`, Hibernate Logger 레벨 재설정으로 로그 볼륨을 줄였습니다.

---

## 8. Docker Desktop Minikube에서 port-forward로 API 검증

**배경**
NodePort 30084로 직접 접근이 되지 않아 배포 검증 방법 결정 필요.

**선택지**
- A: `minikube tunnel` 실행 후 NodePort 접근
- B: `kubectl port-forward`로 로컬 포트 포워딩
- C: `minikube service` 명령으로 URL 자동 생성

**선택: B**

**근거**
`port-forward`는 추가 데몬 실행 없이 즉시 동작하며, 포트 번호를 그대로 유지할 수 있어 curl 명령 재작성이 불필요합니다.
`minikube tunnel`은 sudo 권한이 필요하고 백그라운드 프로세스를 유지해야 합니다.
API 검증 목적으로는 `port-forward`가 가장 빠르고 간단합니다.

---

## 13. shipment-event 상태 반영 — logistics-api HTTP 직접 호출 대신 order-api consume 방식 선택

**배경**
logistics-api에서 배송 상태가 변경될 때 order-api의 `orders.shipment_status`를 갱신해야 합니다.
이를 위해 logistics-api가 order-api를 직접 호출하는 방식과 이벤트를 발행해 order-api가 수신하는 방식 중 선택이 필요했습니다.

**선택지**
- A: logistics-api가 배송 상태 변경 후 order-api에 HTTP로 직접 호출해 주문 상태 업데이트
- B: logistics-api가 shipment-event를 발행하고, order-api가 이를 consume해 자체 DB에 반영

**선택: B**

**근거**
MSA 원칙상 `orders` 테이블은 order-api만 소유합니다.
logistics-api가 order-api를 HTTP로 직접 호출하면 logistics-api → order-api 단방향 의존이 생기고,
order-api가 이미 logistics-api 이벤트를 consume하는 구조와 합쳐지면 양방향 결합이 됩니다.
또한 HTTP 호출은 응답 대기 시간 동안 DB 트랜잭션을 점유하거나 장애를 직접 전파할 수 있습니다.

이벤트 발행 방식은 이미 logistics-api에 적용된 Transactional Outbox 패턴을 그대로 재사용하며,
order-api도 기존 Kafka consumer 인프라를 갖추고 있어 추가 인프라 비용 없이 연결이 가능합니다.
배송 상태 변경과 이벤트 저장을 같은 트랜잭션에서 처리하므로 이벤트 유실 없이 주문에 반영됩니다.

**결과**
logistics-api는 배송 상태 변경 시 outbox에 shipment-event를 저장하고,
order-api `ShipmentEventConsumer`가 이를 수신해 `orders.shipment_status`를 갱신합니다.
두 서비스의 DB는 서로 직접 접근하지 않으며, 결합 방향은 order-api → (Kafka) ← logistics-api로 단방향을 유지합니다.

---

## 14. Outbox 발행 잠금 방식 — JPA PESSIMISTIC_WRITE에서 native claim으로 전환

**배경**
`OutboxPublisherJob`이 PENDING 이벤트를 가져올 때 멀티 Pod 환경에서 동일 이벤트가 중복 처리되지 않도록 잠금 방식이 필요했습니다.
초기 구현은 JPA `PESSIMISTIC_WRITE` 락을 사용했지만 실제 배포 환경에서 한계가 드러났습니다.

**선택지**
- A: JPA `PESSIMISTIC_WRITE` + `jakarta.persistence.lock.timeout` 힌트 기반 잠금 후 폴링
- B: `FOR UPDATE SKIP LOCKED + UPDATE ... RETURNING` 네이티브 쿼리로 원자적 claim

**선택: B**

**근거**
`PESSIMISTIC_WRITE` 방식은 이미 잠긴 행을 기다립니다.
멀티 Pod 환경에서 모든 인스턴스가 같은 행에 대해 직렬 대기하면,
처리량이 낮아지고 잠금 대기 중에도 DB 커넥션을 점유(idle in transaction)하는 문제가 생깁니다.
힌트 키(`jakarta.persistence.lock.timeout`)가 실제로 적용되는지 런타임에 검증하기 어렵고,
Hibernate 6의 힌트 처리 방식에 따라 silently 무시될 수 있어 안정성 보장이 불확실했습니다.

`FOR UPDATE SKIP LOCKED`는 이미 잠긴 행을 즉시 건너뜁니다.
각 Pod가 겹치지 않는 배치를 경합 없이 가져가므로 idle in transaction이 발생하지 않습니다.
`UPDATE ... RETURNING`으로 claim과 `PROCESSING` 상태 전환을 한 문장에서 원자적으로 처리하고,
claim 만료 시각(`next_retry_at = now + 2분`)을 기록해 stale recovery의 기준 시각으로도 활용합니다.

**결과**
멀티 Pod 환경에서 중복 클레임 없이 원자적 배치 처리가 가능해졌습니다.
idle in transaction 구간이 제거되었고, claim 만료 시각 기록으로 `StaleOutboxRecoveryJob`이
Pod 장애로 stuck된 PROCESSING 이벤트를 자동 복구하는 기반이 마련되었습니다.

---

## 15. 관리자용 Outbox API — /admin/outbox 분리 경로와 단건/배치 엔드포인트 분할

**배경**
운영 중 FAILED 상태로 전환된 Outbox 이벤트를 DB 직접 접근 없이 확인하고 재처리할 수단이 필요했습니다.
이를 기존 도메인 API에 통합할지, 별도 경로로 분리할지 결정이 필요했습니다.

**선택지**
- A: 기존 도메인 API 경로에 조회·재처리 기능 통합
- B: `/admin/outbox` 경로로 분리하고 조회, 단건 재처리, 배치 재처리를 독립 엔드포인트로 분할

**선택: B**

**근거**
Outbox 이벤트 조회와 재처리는 일반 사용자가 호출하는 도메인 API와 목적이 다릅니다.
운영자 복구용 기능으로 분리하면 나중에 인증·접근 제어를 `/admin` 경로 단위로 일괄 적용할 수 있습니다.

단건(`/{id}/retry`)과 배치(`/retry`)를 별도 엔드포인트로 나눈 이유는 의도한 범위를 명시적으로 구분하기 위해서입니다.
단건 재처리는 특정 이벤트의 원인을 파악한 뒤 선택적으로 복구할 때,
배치 재처리는 장애 복구 후 FAILED 이벤트를 일괄 재처리할 때 사용합니다.
두 동작을 하나의 엔드포인트로 합치면 호출 의도가 불명확해지고 운영 실수 가능성이 높아집니다.

**결과**
`GET /admin/outbox?status=FAILED&limit=20`으로 상태별 이벤트 목록 조회,
`POST /admin/outbox/{id}/retry`로 단건 재처리,
`POST /admin/outbox/retry?status=FAILED&limit=20`으로 배치 재처리가 가능합니다.
운영자가 DB 직접 접근 없이 FAILED 이벤트를 확인하고 복구할 수 있는 수단이 마련되었습니다.

---

## 16. monolithic instruction 대신 CLAUDE.md + rules + skills 구조를 택한 이유

**배경**
하네스를 처음 구성할 때 CLAUDE.md 하나에 모든 규칙을 담는 방식과,
고수준 원칙만 CLAUDE.md에 두고 세부 규칙을 rules 파일로 분리하는 방식 중 선택이 필요했습니다.

**선택: 분리 구조 (Karpathy 스타일)**

**근거**
모든 규칙을 CLAUDE.md에 담으면 파일이 길어질수록 Claude가 처리해야 할 컨텍스트가 증가합니다.
서비스별로 관련 없는 규칙도 모두 로드됩니다.
반면 path-scoped rules 구조에서는 `order-api/**` 작업 시에는 order-api rule만 로드됩니다.
규칙이 추가되더라도 CLAUDE.md를 건드리지 않고 해당 rules 파일만 수정하면 됩니다.
CLAUDE.md 내용이 안정적으로 유지되어 세션 간 일관성이 높아집니다.

**결과**
서비스 추가(logistics-api, address service)마다 해당 서비스의 rules 파일만 추가하면 됩니다.
CLAUDE.md는 5개 원칙을 유지한 채 변경되지 않습니다.

---

## 17. 생산성 지표를 시간/재작업/범위 이탈 중심으로 설정한 이유

**배경**
하네스 효과를 측정하기 위한 지표를 선택해야 했습니다.
토큰 수, 코드 라인 수, 작업 완료 시간 등 여러 후보가 있었습니다.

**선택: 작업 완료 시간 + 재작업 횟수 + 변경 범위 이탈 수 + 검증 실패 횟수**

**근거**
이 지표들은 Claude Code 하네스가 실제로 영향을 주는 영역을 측정합니다.
- 재작업 횟수: 하네스가 설계 오류를 초기에 잡으면 재작업이 줄어듬
- 범위 이탈 수: Surgical Changes 원칙과 rules 파일이 작동하면 이탈이 줄어듬
- 검증 실패 횟수: 규칙이 올바른 구조를 강제하면 CI/smoke 실패가 줄어듬
- 작업 완료 시간: 총합 효율성을 반영

코드 라인 수는 생산성 지표로 부적합합니다. 라인 수가 많아도 오히려 나쁜 코드일 수 있습니다.

---

## 18. 토큰 사용량을 보조 지표로만 취급한 이유

**배경**
Claude Code를 사용하면 자연스럽게 토큰 사용량이 발생합니다.
하네스 구성(rules, skills 파일 로드)은 컨텍스트 토큰을 증가시킵니다.
이것이 비효율인지 아닌지 판단이 필요했습니다.

**선택: 보조 지표, 단독 비교하지 않음**

**근거**
rules 파일이 많아지면 컨텍스트 토큰이 늘어나지만,
그 토큰이 일관된 지침을 제공하여 재작업을 방지한다면 총 토큰은 오히려 줄어들 수 있습니다.
"규칙을 매 세션마다 자연어로 설명하는 토큰" vs "rules 파일 로드 토큰" 비교가 의미 있습니다.
토큰 자체보다 "그 토큰이 유효한 지침을 제공했는가"가 더 중요한 측정 기준입니다.
토큰 사용량은 작업 복잡도 차이를 통제하기 어려워 단독 비교는 왜곡이 발생합니다.

---

## 19. 수동 검증을 smoke script로 끌어올린 이유

**배경**
address service 연동 검증은 처음에 수동 curl 명령 가이드로 문서화되었습니다.
이후 smoke script로 자동화할지, 수동 가이드로 유지할지 선택이 필요했습니다.

**선택: smoke script 자동화**

**근거**
수동 curl 가이드는 재현은 가능하지만, 실행할 때마다 명령을 복사-붙여넣기해야 합니다.
환경 설정(order-api http 전환, rollout 대기, stub 복원)을 잊거나 순서를 바꾸면 검증이 의미 없어집니다.
smoke script는 실행 순서를 강제하고, `trap cleanup EXIT`으로 stub 복원을 보장합니다.
한 번 작성하면 GitHub Actions에 연결하여 재사용할 수 있습니다.

반면 smoke script는 실행 환경(minikube, docker, kubectl)이 준비되어 있어야 합니다.
CI/CD 파이프라인에서 항상 실행하기 어려운 환경이라면 수동 가이드와 병행하는 것이 현실적입니다.

**결과**
`e2e-order-address-http-smoke.sh`가 7단계 검증 절차를 자동화합니다.
수동 가이드는 README에 유지하되, "이 과정을 스크립트로 자동화"라는 참조를 추가했습니다.
GitHub Actions `smoke-tests.yml`에 `address-http` 시나리오로 연결했습니다.
