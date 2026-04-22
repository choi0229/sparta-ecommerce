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
