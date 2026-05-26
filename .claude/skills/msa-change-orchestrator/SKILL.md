---
name: msa-change-orchestrator
description: MSA 서비스 변경 작업(신규 서비스 추가, 기능 변경, 이벤트 계약 수정, 테스트 보강)에 설계→구현→리뷰→검증 흐름을 순서대로 적용할 때 사용합니다.
---

# msa-change-orchestrator Skill

설계→구현→리뷰→검증 흐름을 순서대로 적용합니다. 각 Phase는 이전 Phase의 산출물을 입력으로 받습니다.

---

## 시작 전 확인 체크리스트

다음 항목이 명확하지 않으면 진행 전 사용자에게 확인합니다.

| 항목 | 확인 질문 |
|---|---|
| 변경 대상 서비스 | 어떤 서비스를 수정 또는 추가하는가? |
| 기존 서비스 영향 | product-api, order-api, inventory-api 코드 변경이 필요한가? |
| 이벤트 계약 변경 | Kafka 이벤트 페이로드 구조가 바뀌는가? |
| 배포 필요 여부 | Minikube 배포 검증까지 필요한가? |
| 테스트 범위 | 단위 / Outbox 검증 / 통합 테스트 중 어디까지 필요한가? |

---

## Phase 1 — 설계 검토 (msa-architect)

**참조**: `.claude/agents/msa-architect.md`, `.claude/rules/kafka-outbox-saga.md`, `CLAUDE.md`

수행 항목:
- 도메인 경계 명확화 (서비스가 소유할 데이터)
- 이벤트 계약 변경 시 Producer/Consumer 양쪽 영향 범위 확인
- Outbox 패턴 적용 위치, 멱등성 처리 방식 결정
- `@Transactional` 경계 및 외부 호출 분리 방식 결정

산출물:
```
[DECISION] 도메인 경계: <명시>
[DECISION] 이벤트 계약: <변경 여부 + 페이로드 구조>
[DECISION] 트랜잭션 경계: <범위 + 외부 호출 분리 방식>
[SKIP] 해당 없는 항목
```

> 이벤트 계약 변경이 없고 도메인 경계가 명확한 경우 Phase 1을 간소화할 수 있습니다.

---

## Phase 2 — 구현 (backend-builder)

**참조**: `.claude/agents/backend-builder.md`, `.claude/rules/<대상 서비스>.md`, `.claude/skills/java-coding/`

수행 항목:
- Phase 1 결정 범위 내에서만 Spring Boot 코드 작성
- Flyway migration 작성
- `@ExtendWith(MockitoExtension.class)` 기반 단위 테스트 작성

금지:
- Phase 1에서 결정되지 않은 이벤트 계약 변경
- 요청 범위를 벗어난 리팩토링
- 기존 서비스(order-api 등) 코드 수정

산출물: 수정/추가 파일 목록, 테스트 클래스 목록, migration 파일명

---

## Phase 3 — 리뷰 (code-reviewer)

**참조**: `.claude/agents/code-reviewer.md`, `.claude/rules/kafka-outbox-saga.md`

8개 체크포인트를 순서대로 확인합니다:

1. `@Transactional` 내부에서 Kafka 대기, HTTP 호출, Elasticsearch 호출 없음
2. `REQUIRES_NEW` 메서드가 같은 Bean에서 자기 호출되지 않음
3. Outbox 저장이 도메인 상태 변경과 같은 트랜잭션에 있음
4. IdempotencyRecord 처리가 단일 트랜잭션으로 묶여 있음
5. Controller가 JPA Entity를 직접 반환하지 않음
6. 생성자 주입 사용, 필드 주입 없음
7. 기존 서비스 코드(order-api 등)가 수정되지 않음
8. 이벤트 페이로드 구조가 기존 계약과 일치함

산출물:
```
[PASS] 항목명 — 이유
[FAIL] 항목명 — 문제 설명 + backend-builder에게 전달할 수정 지침
[WARN] 항목명 — 추후 개선 권장
```

> `[FAIL]` 항목이 있으면 backend-builder에 수정 지침을 전달하고 Phase 2로 돌아갑니다.

---

## Phase 4 — 검증 계획 (qa)

**참조**: `.claude/agents/qa.md`, `scripts/claude-guardrails.sh`, `.github/workflows/claude-ci-gate.yml`

수행 항목:
- 테스트 공백 분류
  - P0: 엔티티·서비스 계층 (외부 의존성 없음) — 즉시 작성
  - P1: Consumer·Scheduler (Kafka mock 포함) — 다음 단계
  - P2: 통합 테스트 (실제 DB/Kafka) — 별도 계획
- guardrails 체크 항목 확인 (`bash scripts/claude-guardrails.sh`)
- CI Gate 통과 조건 확인

산출물:
```
[P0] 즉시 작성할 테스트 목록
[P1] 이후 추가할 테스트 목록
[GUARDRAILS] 이상 없음 / 주의 항목
[CI GATE] 통과 조건 요약
[DEPLOY] 필요 / 불필요
```

---

## Phase 5 — 배포 검증 (선택)

`[DEPLOY] 필요` 판정 시에만 실행합니다. **사용자 승인 후** `deploy-api` Skill로 진행합니다.

```bash
./scripts/redeploy-api.sh <서비스명>
```

배포 후 확인 순서:
1. `kubectl get pod -n ecommerce -l app=<서비스>` — Running 확인
2. `kubectl logs -n ecommerce -l app=<서비스> --tail=50` — 기동 로그 확인
3. `curl http://localhost:<port>/actuator/health` — DB UP 포함 확인

---

## 작업 유형별 시작 Phase

| 작업 유형 | 시작 Phase | 건너뛸 수 있는 Phase |
|---|---|---|
| 신규 서비스 추가 | 1 | — |
| 기존 서비스 기능 변경 | 1 | — |
| 이벤트 계약 수정 | 1 (필수) | 5 (배포 결정에 따라) |
| 테스트 보강만 | 4 | 1, 2 |
| 리뷰만 필요 | 3 | 1, 2, 4 |
| 배포만 필요 | 5 | 1~4 |
