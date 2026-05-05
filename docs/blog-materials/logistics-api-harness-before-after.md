# 하네스 고도화 전후 비교

Claude Code 하네스를 revfactory/harness 개념을 참고해 고도화하면서 달라진 점을 정리했습니다.

---

## 1. 한눈에 보기: 비교표

| 항목 | 기존 하네스 | 고도화 후 하네스 |
|---|---|---|
| **역할 분리** | 없음 — 하나의 세션이 설계·구현·리뷰·검증 모두 수행 | agents 4개로 역할 명시적 분리 |
| **설계 검토** | 구현 중 또는 구현 후 사후 확인 | msa-architect가 도메인 경계·이벤트 계약 사전 검토 |
| **구현 제약** | 별도 구현 전담 역할 없음 | backend-builder: 임의 리팩토링·이벤트 계약 변경 금지 명시 |
| **리뷰** | 명시적 체크포인트 없음 | code-reviewer: 8개 체크포인트 기반 read-only 리뷰 |
| **테스트 계획** | 필요 시 임의 추가 | qa: P0/P1/P2 우선순위로 공백 식별 및 계획 수립 |
| **작업 흐름** | 매 세션마다 프롬프트로 흐름 설명 필요 | 설계→구현→리뷰→검증 흐름이 agents로 고정 |
| **규칙 관리** | CLAUDE.md, rules/, skills/ | 동일 + agents/가 역할별 entry point로 위임 구조 |
| **컨텍스트 효율** | rules/skills 전체가 항상 로딩될 수 있음 | agent → rules/skills 위임으로 필요 시만 참조 |
| **guardrails** | macOS grep -P 미지원 (Perl 정규식) | grep -E + POSIX [[:space:]] 로 macOS/Linux 통일 |
| **자동화 수준** | CI Gate, guardrails로 검증 자동화 | 동일 + agents 흐름 추가 + msa-change-orchestrator Skill 기본 구현 |

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

## 4. 고도화 후 달라진 작업 흐름

### 기존 흐름
```
하나의 세션
  ├── 도메인 경계 검토 (불명확)
  ├── 구현
  ├── 리뷰 (체크포인트 없음)
  └── 테스트 (임의 추가)
```

### 고도화 후 흐름
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

---

## 5. 구체적으로 좋아진 점

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
상세 규칙은 `rules/kafka-outbox-saga.md`, `rules/logistics-api.md` 등에 위임합니다.
세션 컨텍스트에서 불필요한 규칙이 중복 로딩되는 비용을 줄입니다.

**guardrails 플랫폼 통일**
`grep -P` (Perl) → `grep -E` + `[[:space:]]` (POSIX) 전환으로
macOS 로컬과 GitHub Actions(Ubuntu) 양쪽에서 동일하게 동작합니다.

---

## 6. 남은 한계와 다음 단계

| 항목 | 현재 상태 | 다음 단계 |
|---|---|---|
| **Orchestrator Skill** | 기본 구현됨 (`msa-change-orchestrator` Skill 추가) | 추가 고도화 (fan-out/fan-in 자동화 등) |
| **ops agent** | 보류 | 배포·모니터링·장애 대응 자동화 역할 |
| **harness-evolve Skill** | 보류 | 하네스 구조 자체를 점진 개선하는 Skill |
| **다른 서비스 CI 연결** | logistics-api만 연결됨 | product-api, order-api, inventory-api CI 단계적 추가 |
| **통합 테스트** | 수동 검증 완료, 자동화 미적용 | Kafka Consumer E2E 자동화 테스트 |

현재 하네스는 "역할 분리, 규칙 기반 제어, agents 간 흐름 자동화"까지 구현된 상태입니다.
다음 단계는 Kafka E2E 통합 테스트 자동화와 다른 서비스 CI 연결 확장입니다.
