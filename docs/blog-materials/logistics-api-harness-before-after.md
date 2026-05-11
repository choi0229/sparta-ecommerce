# 하네스 고도화 전후 비교

Claude Code 하네스를 단계적으로 고도화하면서 달라진 점을 정리했습니다.
초기에는 revfactory/harness 개념을 참고해 roles/agents 기반 구조를 먼저 도입했고, 이후에는 **Karpathy 스타일 CLAUDE.md**를 추가해 상위 원칙과 상세 규칙의 경계를 더 명확하게 정리했습니다.

즉 현재 하네스는 단순한 “프롬프트 묶음”이 아니라,

- 역할 분리
- 규칙 기반 제어
- 얇은 CLAUDE.md + rules/skills 위임 구조
- smoke / workflow / self-hosted runner 기반 검증 자동화

까지 포함하는 구조로 발전한 상태입니다.

---

## 1. 한눈에 보기: 비교표

| 항목 | Phase 1 — 기존 하네스 | Phase 2 — revfactory/harness 개념 반영 | Phase 3 — Karpathy 스타일 `CLAUDE.md` 추가 이후 |
|---|---|---|---|
| **역할 분리** | 없음 — 하나의 세션이 설계·구현·리뷰·검증 모두 수행 | agents 4개로 역할 명시적 분리 | 동일 + 역할별 흐름이 더 고정되고 안정화 |
| **설계 검토** | 구현 중 또는 구현 후 사후 확인 | msa-architect가 도메인 경계·이벤트 계약 사전 검토 | 동일 + CLAUDE.md에서 “Think Before Coding” 원칙으로 설계 선확인 강화 |
| **구현 제약** | 별도 구현 전담 역할 없음 | backend-builder: 임의 리팩토링·이벤트 계약 변경 금지 명시 | 동일 + CLAUDE.md에서 최소 변경, 단순성 우선 원칙으로 상위 제약 추가 | 
| **리뷰** | 명시적 체크포인트 없음 | code-reviewer: 8개 체크포인트 기반 read-only 리뷰 | 동일 + smoke/workflow 운영 검증까지 연결 |
| **테스트 계획** | 필요 시 임의 추가 | qa: P0/P1/P2 우선순위로 공백 식별 및 계획 수립 | 동일 + smoke / integration workflow로 실제 실행 경로 구체화 |
| **작업 흐름** | 매 세션마다 프롬프트로 흐름 설명 필요 | 설계→구현→리뷰→검증 흐름이 agents로 고정 | 동일 + CLAUDE.md가 공통 원칙 entry point 역할 수행 |
| **규칙 관리** | CLAUDE.md, rules/, skills/ | 동일 + agents/가 역할별 entry point로 위임 구조 | CLAUDE.md는 얇게 유지, 상세는 rules/skills로 위임 |
| **컨텍스트 효율** | rules/skills 전체가 항상 로딩될 수 있음 | agent → rules/skills 위임으로 필요 시만 참조 | CLAUDE.md는 고수준 원칙만 유지해 중복 로딩 비용 추가 절감 |
| **guardrails** | macOS grep -P 미지원 (Perl 정규식) | grep -E + POSIX [[:space:]] 로 macOS/Linux 통일 | 동일 |
| **자동화 수준** | CI Gate, guardrails로 검증 자동화 | 동일 + agents 흐름 추가 + msa-change-orchestrator Skill 기본 구현 | 동일 + smoke (happy/negative/address-http/all), integration workflow, artifact 보존, self-hosted runner 운영 추가 |
| **smoke workflow** | 없음 | 부분 수동 검증 | happy / negative / address-http / all 시나리오 운영 |
| **integration workflow** | 없음 | 없음 | 서비스별 test / integrationTest 분기 구성 |
| **self-hosted runner** | 없음 | 없음 | 등록 완료, smoke workflow 실제 실행 검증 완료 |
| **결과 보존** | 세션 로그 의존 | 동일 | smoke 로그 artifact 업로드로 실행 결과 보존 가능 |

---

## 2. 기존 하네스의 강점과 한계

### 강점
- `CLAUDE.md`: 전체 프로젝트 핵심 원칙 중앙화
- `rules/*.md`: Kafka/Outbox/Saga, 서비스별 규칙 분리
- `skills/`: 반복 작업(배포, 서비스 생성) 절차 재사용
- `settings.json`: `rm -rf`, `DROP TABLE` 등 위험 명령 실행 차단
- `guardrails.sh` + CI Gate: 커밋/PR 단계 자동 검증

### 한계
- **역할 경계 없음**: 하나의 세션이 도메인 설계, Spring Boot 구현, 리뷰, 테스트 계획을 동시에 담당
- **설계 검토가 사후적**: 구현 중 또는 구현 후에야 트랜잭션 경계·이벤트 계약 문제를 발견
- **리뷰 체크포인트 비고정**: 리뷰 기준이 매번 프롬프트 내용에 따라 달라짐
- **테스트 우선순위 불명확**: 어떤 테스트를 먼저 작성해야 하는지 기준이 없었음
- **공통 원칙과 상세 규칙의 경계가 약함**: 세션마다 어느 파일을 우선 참고해야 하는지 흔들릴 수 있었음

---

## 3. revfactory/harness에서 참고한 개념

전체 자동 하네스 생성기, fan-out/fan-in 구조는 도입하지 않았습니다.
현재 프로젝트에 맞게 다음 개념만 선택적으로 적용했습니다.

| 참고 개념 | 적용 방식 |
|---|---|
| **Agent team architecture** | msa-architect, backend-builder, code-reviewer, qa 4개 역할로 분리 |
| **역할별 agent 분리** | 각 agent는 자신의 역할 외 코드 수정·배포 실행 금지 명시 |
| **Generator-Verifier 패턴** | backend-builder(생성) → code-reviewer(검증) 분리 |
| **Progressive Disclosure** | agent 파일은 짧게 유지, 상세 규칙은 rules/skills에 위임 |
| **Feedback/Evolution Loop** | qa agent가 테스트 공백을 P0/P1/P2로 분류해 점진 보강 유도 |

---

## 4. Karpathy 스타일 `CLAUDE.md` 추가 이후 달라진 점
Phase 2에서 역할 분리를 만들었다면, Phase 3에서는 **Karpathy 스타일 CLAUDE.md**를 추가해
“공통 원칙은 얇게, 상세 규칙은 분리해서” 관리하는 구조를 더 분명하게 만들었습니다.

**핵심 변화**

**1) `CLAUDE.md`의 역할이 더 선명해짐**

기존에는 `CLAUDE.md`가 공통 원칙과 상세 규칙을 함께 담는 경향이 있었습니다.
Karpathy 스타일을 반영한 이후에는 `CLAUDE.md`를 다음과 같은 상위 원칙 중심 문서로 정리했습니다.
- **Think Before Coding**
- **Simplicity First**
- **Surgical Changes**
- **Goal-Driven Execution**
- **Project Context**
즉, `CLAUDE.md`는 “이 프로젝트에서 코드를 어떻게 접근해야 하는가”를 정의하고,
구체적인 도메인 규칙은 `rules/*.md`, 반복 절차는 `skills/*.md`를 보도록 역할을 나눴습니다.

**2) 진입점과 상세 규칙의 분리**

이전에는 세션마다 많은 규칙을 한 번에 읽어야 할 수 있었지만,
지금은 다음 구조가 더 분명해졌습니다.

- `CLAUDE.md` → 고수준 공통 원칙
- `agents/*.md` → 역할별 시작점
- `rules/*.md` → 서비스/도메인 규칙
- `skills/*.md` → 반복 절차

이 구조 덕분에 **필요한 규칙만 단계적으로 참조**할 수 있게 되었고,
세션 컨텍스트 낭비를 줄일 수 있었습니다.

**3) 공통 원칙의 일관성 강화**

Karpathy 스타일 `CLAUDE.md` 추가 이후, 모든 작업은 공통적으로 다음 제약 아래에서 진행되도록 정리되었습니다.
- 요구사항을 조용히 가정하지 않기
- 리스크 큰 변경은 먼저 확인하기
- 최소 수정 우선
- 불필요한 추상화/비동기화/구조 추가 금지
- 변경 범위를 요청 범위 안으로 제한
- 성공 기준을 먼저 정의하고 검증하기
즉, agents가 역할을 분리했다면, `CLAUDE.md`는 그 모든 역할이 공통적으로 지켜야 할 상위 행동 원칙을 고정해주는 역할을 합니다.

---

## 5. 고도화 후 달라진 작업 흐름

### Phase 1 — 기존 흐름
```
하나의 세션
  ├── 도메인 경계 검토 (불명확)
  ├── 구현
  ├── 리뷰 (체크포인트 없음)
  └── 테스트 (임의 추가)
```

### Phase 2 — agents 도입 후 흐름
```
msa-architect
  └── 도메인 경계, 이벤트 계약, Saga/Outbox 구조 검토
        ↓
backend-builder
  └── Spring Boot 구현, Flyway 마이그레이션, 단위 테스트 작성
        ↓
code-reviewer
  └── @Transactional 경계, REQUIRES_NEW self-invocation,
      Outbox 동일 트랜잭션, Entity 직접 노출 등 8개 체크포인트 리뷰
        ↓
qa
  └── 테스트 공백 P0/P1/P2 분류, CI Gate 검증, 배포 체크리스트
```

### Phase 3 — Karpathy 스타일 `CLAUDE.md` 추가 후 흐름
```
CLAUDE.md
  └── Think Before Coding / Simplicity First / Surgical Changes
        ↓
msa-architect
  └── 도메인 경계, 이벤트 계약, 구조 판단
        ↓
backend-builder
  └── 최소 변경 원칙 기반 구현
        ↓
code-reviewer
  └── 정적 체크포인트 리뷰
        ↓
qa
  └── 테스트 공백 정리 + smoke / integration 검증 계획
        ↓
workflow / runner
  └── happy / negative / address-http / all 실행
      integration-tests 분기 실행
      artifact 보존
```

---

## 6. 구체적으로 좋아진 점

**설계와 구현 분리**
msa-architect가 이벤트 계약·도메인 경계를 사전 검토하므로
backend-builder가 구현 중 설계 의사결정을 병행하는 혼란이 줄었습니다.

**리뷰 체크포인트 반복 가능**
code-reviewer의 8개 체크포인트는 매 작업마다 동일한 기준으로 실행됩니다.
`PESSIMISTIC_WRITE` 잠금 누락, `REQUIRES_NEW` self-invocation 같은 비자명한 문제를
코드 수정 없이 발견할 수 있는 구조가 되었습니다.

**테스트 공백의 우선순위화**
qa agent가 커버리지 공백을 P0(엔티티·서비스)/P1(Consumer·Scheduler)/P2(통합)로 분류했습니다.
한 번에 모두 작성하는 대신 P0부터 순서대로 추가할 수 있었습니다.

**컨텍스트 효율**
각 agent 파일은 역할 정의와 참조 경로만 포함합니다.
상세 규칙은 `rules/kafka-outbox-saga.md`, `rules/logistics-api.md`, `skills/*.md` 등에 위임합니다.
Karpathy 스타일 `CLAUDE.md` 추가 이후에는 `CLAUDE.md`까지 얇게 유지되면서
세션 컨텍스트에서 불필요한 규칙이 중복 로딩되는 비용을 더 줄일 수 있었습니다.

**guardrails 플랫폼 통일**
`grep -P` (Perl) → `grep -E` + `[[:space:]]` (POSIX) 전환으로
macOS 로컬과 GitHub Actions(Ubuntu) 양쪽에서 동일하게 동작합니다.

**운영 검증까지 포함한 자동화**
고도화 이후에는 단순한 코드 리뷰를 넘어 실제 검증 자동화까지 연결되었습니다.
- smoke-tests.yml
  - happy
  - negative
  - address-http
  - all
- integration-tests.yml
  - 서비스별 test / integrationTest 분기
- self-hosted runner 등록
- smoke 실행 로그 artifact 업로드
즉, “코드 작성”뿐 아니라 실행 결과 보존과 운영 검증까지 하네스의 일부가 되었습니다.

---

## 7. 남은 한계와 다음 단계

| 항목 | 현재 상태 | 다음 단계 |
|---|---|---|
| **Orchestrator Skill** | 기본 구현됨 (`msa-change-orchestrator` Skill 추가) | 추가 고도화 (fan-out/fan-in 자동화 등) |
| **ops agent** | 보류 | 배포·모니터링·장애 대응 자동화 역할 |
| **harness-evolve Skill** | 보류 | 하네스 구조 자체를 점진 개선하는 Skill |
| **다른 서비스 CI 연결** | integration-tests workflow는 구성됨, CI Gate는 logistics-api 중심 | product-api, order-api, inventory-api CI 단계적 확장 |
| **통합 테스트** | 수동 검증 완료, workflow 구성 완료 | Kafka Consumer E2E 자동화 테스트 |
| **smoke 결과 활용** | artifact 보존까지 구현 | run 간 diff 비교, 실행 결과 요약 자동화 |
| **self-hosted runner 운영** | 등록 및 실행 성공 | 오프라인 감지 기준 및 대응 절차 정리 |

현재 하네스는 "역할 분리, 규칙 기반 제어, agents 간 흐름 자동화"를 넘어서,
"**얇은 CLAUDE.md 기반 공통 원칙, rules/skills 위임, smoke/workflow/self-hosted runner까지 연결된 운영 가능한 검증 구조**"까지 구현된 상태입니다.

다음 단계는

- Kafka E2E 통합 테스트 자동화
- 다른 서비스 CI 연결 확장
- runner 운영 안정화
- 실행 결과 요약/비교 자동화

로 이어질 수 있습니다.
