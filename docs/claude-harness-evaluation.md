# Claude Code 하네스 진화 평가

최초 작성: 2026-04-21 (Phase 2 기준)
갱신: 2026-05-10 (Phase 3 반영)
대상 브랜치: dev-logistical

---

## 문서 목적

이 문서는 sparta-ecommerce 프로젝트에서 Claude Code 하네스가 어떻게 진화했는지를 단계별로 평가합니다.
각 Phase에서 무엇이 추가되었고, 어떤 한계가 있었으며, 다음 단계에서 무엇이 달라졌는지를 기록합니다.

개발 생산성 지표의 상세 정의와 Before/After 비교는
`docs/blog-materials/claude-harness-productivity-metrics.md`를 참조하세요.

---

## Phase 1 — 기본 Claude Code

### 설명

하네스 없이 Claude Code를 코드 생성 도구로만 사용하는 단계입니다.

### 특징

- 매 세션마다 프로젝트 규칙을 자연어로 설명
- 규칙 해석이 세션마다 달라질 수 있음
- 기존 서비스 코드를 의도치 않게 수정할 가능성 존재
- 배포 절차가 일관되지 않을 수 있음

### 평가

별도 평가 수행 없음. Phase 2와의 비교 기준점으로 활용합니다.

---

## Phase 2 — 구조적 하네스 도입

### 설명

revfactory/harness 구조를 참조해 다음 구성 요소를 갖춘 단계입니다.

| 구성 요소 | 역할 |
|---|---|
| `CLAUDE.md` | 전체 프로젝트 핵심 원칙 |
| `.claude/rules/*.md` | 서비스별/주제별 path-scoped 작업 규칙 |
| `.claude/skills/` | 반복 작업 절차 재사용 |
| `.claude/agents/*.md` | 역할별 agent 명세 (msa-architect, backend-builder, code-reviewer, qa) |
| `.claude/settings.json` | 위험 명령 차단/승인 경계 |
| `scripts/claude-guardrails.sh` | 커밋 전 안전 검사 |
| `.github/workflows/claude-ci-gate.yml` | PR마다 자동 검증 |

평가 기준일: 2026-04-21

### Phase 2 평가 요약

| 항목 | 점수 | 상태 |
|---|---|---|
| CLAUDE.md 전역 규칙 | 4 / 5 | 양호 |
| path-scoped rules | 4 / 5 | 양호 |
| skills 분리 | 4 / 5 | 양호 |
| Kafka/Outbox/Saga 공통 규칙 단일화 | 5 / 5 | 완비 |
| permissions boundary | 3 / 5 | 개선 필요 |
| guardrails script | 3 / 5 | 개선 필요 |
| GitHub Actions CI Gate | 4 / 5 | 양호 |
| 테스트/빌드 검증 | 4 / 5 | 양호 |
| 기존 서비스 보호 | 4 / 5 | 양호 |
| 피드백 루프 | 3 / 5 | 개선 필요 |

**Phase 2 평균: 3.8 / 5**

### Phase 2 항목별 평가

#### 1. CLAUDE.md 전역 규칙 — 4 / 5

**근거**
- 서비스별 책임 경계(도메인 소유, DB 직접 접근 금지, 이벤트 기반 통신)가 명확히 기술됨
- `@Transactional` 내 외부 호출 금지, 스냅샷 저장, 예약-보상 흐름 등 핵심 원칙 포함
- 작업 방식(리팩토링 금지, 엔티티 직접 노출 금지, 생성자 주입)과 테스트/배포 절차 문서화

**남은 개선점**
- 서비스별 포트 번호, DB 이름, Kafka 토픽 이름 등 빠르게 참조할 수 있는 레퍼런스 테이블 부재
- `payment-api`를 만들지 않는 이유, `product-api` 내 약식 결제 흐름의 범위가 암묵적임

---

#### 2. path-scoped rules — 4 / 5

**근거**
- `kafka-outbox-saga.md`: Kafka 이벤트, Outbox, processed_event, Saga, 트랜잭션 경계 규칙을 서비스 횡단으로 단일화
- `logistics-api.md`: 배송 상태 전이, 멱등성, Outbox, 트랜잭션, 이력 규칙을 logistics-api 전용으로 명세
- `performance-observability.md`: 성능 수치 미검증 주장 금지, 측정 근거 요구 기준 포함

**남은 개선점**
- `order-api`, `product-api`, `inventory-api` 전용 path-scoped rule 부재

---

#### 3. skills 분리 — 4 / 5

**근거**
- `create-logistics-api`: 생성 전 확인 항목, 원칙, 패키지 구조, 생성 범위, 금지 사항, 후속 수정 파일을 구체적으로 명세
- `deploy-api`: 지원 서비스 목록(logistics-api 포함), 예시 명령어 포함
- `java-coding`: Java 코딩 스타일 규칙 별도 분리

**남은 개선점**
- skill 간 공통 원칙(트랜잭션, 멱등성)이 중복될 수 있어 rules 파일 참조 형태로 정리하면 유지보수 용이

---

#### 4. Kafka/Outbox/Saga 공통 규칙 단일화 — 5 / 5

**근거**
- `kafka-outbox-saga.md` 한 파일에 이벤트 원칙, 이벤트 계약, processed_event, Outbox, Saga/보상, 트랜잭션 경계, 테스트 규칙을 모두 포함
- 중복 정의 없이 logistics-api rule이 이 파일을 명시적으로 참조하는 구조
- `@Transactional` 내 금지 외부 호출 목록이 구체적
- 보상 처리 멱등성, Consumer 재시작 후 재처리 등 실제 장애 시나리오까지 포함

---

#### 5. permissions boundary — 3 / 5

**근거**
- `allow`: 파일 읽기, git 조회, gradlew test/build, guardrails 실행을 명시적으로 허용
- `deny`: `rm -rf`, `kubectl delete`, `docker system prune`, SQL 파괴 명령, `.env` 읽기를 차단
- `docker build`, `kubectl apply`, `redeploy-api.sh` 등 배포 명령은 사용자 승인 프롬프트

**남은 개선점**
- glob 패턴이 bash 패턴과 다르게 동작할 수 있어 실제 차단 여부를 직접 확인 필요
- `settings.local.json` 오버라이드로 deny 규칙이 무력화될 수 있음

---

#### 6. guardrails script — 3 / 5

**근거**
- 5개 검사 항목(.DS_Store, .env/secret, payment-api, claude-sessions, 위험 명령)을 체계적으로 구성
- CI/로컬 모드 자동 분기 (`CI` 환경변수 기반)
- `set -euo pipefail` 적용

**남은 개선점**
- `grep -P`(Perl regex)는 macOS 기본 grep에서 지원되지 않아 로컬 실행 시 실패 가능 → 이후 `grep -E` + POSIX로 수정됨
- 위험 패턴이 Markdown 문서의 설명 문구를 오탐하는 문제 → 확장자 기반 제외로 수정됨

---

#### 7. GitHub Actions CI Gate — 4 / 5

**근거**
- `guardrails → logistics-api-test → logistics-api-build` 3단계 체인으로 빠른 실패(fast-fail) 구조
- `fetch-depth: 2`로 `HEAD~1` diff 가용성 보장
- Java 17 + temurin + `cache: gradle`로 빌드 캐시 효율화
- push/PR 양쪽 트리거 적용

**남은 개선점**
- 현재 logistics-api만 CI 대상 — order-api, inventory-api, product-api 테스트 job 부재

---

#### 8. 테스트/빌드 검증 — 4 / 5

**근거**
- `LogisticsTransactionalServiceTest`: 5개 단위 테스트, DB 불필요
- 멱등성 4개 케이스 + 중복 orderId 케이스 커버
- Minikube 클러스터에서 배포 후 실제 API 동작 검증 완료

**남은 개선점**
- 다른 서비스(order-api, inventory-api, product-api) CI 테스트 미연결
- Testcontainers 기반 통합 테스트 미구성

---

#### 9. 기존 서비스 보호 — 4 / 5

**근거**
- logistics-api가 order-api/inventory-api/product-api DB에 직접 접근하는 코드 없음
- 이벤트 페이로드를 `OrderCreatedPayload` 내부 record로 격리하여 order-api 코드 의존성 없음

**남은 개선점**
- `order-create-event` payload 구조 변경 시 logistics-api가 조용히 null orderId로 배송 생성할 수 있음

---

#### 10. 피드백 루프 — 3 / 5

**근거**
- 코드 리뷰 → 수정 → 테스트 → 재리뷰 사이클을 2회 반복
- `docs/claude-feedback-log.md`로 발견 사항과 수정 이유를 추적 가능하게 기록

**남은 개선점**
- 피드백 로그 작성이 수동
- 이번 작업에서 발견된 문제들이 rules 파일에 반영되었는지 확인하는 검토 프로세스 미정립

---

## Phase 3 — Karpathy 스타일 CLAUDE.md 정비

평가 기준일: 2026-05-10

### 설명

Phase 2 하네스 구조는 유지하되 두 가지 문제를 해결한 단계입니다.

1. CLAUDE.md가 .gitignore에 포함되어 있어 버전 관리 밖에 있었던 문제 → git 추적 대상으로 전환
2. CLAUDE.md와 rules 파일 간 역할 경계가 흐릿했던 문제 → Karpathy 스타일로 CLAUDE.md를 얇게 정비

추가된 작업:
- `order-api` 전용 path-scoped rule 추가 (`.claude/rules/order-api.md`)
- AddressServiceClient stub/http 설정 기반 분리
- mock address-api (WireMock 기반) 추가
- `e2e-order-address-http-smoke.sh` smoke script 자동화
- smoke-tests.yml에 `address-http` 시나리오 추가

### Phase 3 주요 변화

| 항목 | Phase 2 | Phase 3 |
|---|---|---|
| CLAUDE.md 버전 관리 | .gitignore 포함 (이력 없음) | git 추적 (커밋 이력 존재) |
| CLAUDE.md 구조 | 원칙 + 일부 세부 규칙 혼재 | 고수준 원칙 5개만, 세부는 rules 위임 |
| order-api rules | 없음 | `.claude/rules/order-api.md` 추가 |
| 배포 검증 자동화 | CI Gate (빌드/테스트) | CI Gate + smoke script (3개 시나리오) |
| address client | 없음 | stub/http 설정 분리, mock 서버 포함 |
| smoke workflow 시나리오 | happy / negative / all | + address-http |

### Phase 3 관찰 결과 (정성적 관찰)

- `order-api.md` rules 파일이 추가되어 order-api 작업 시 트랜잭션 경계, 비동기 흐름 규칙이 자동 로드됨
- AddressServiceClient 작업에서 트랜잭션 경계 원칙이 일관되게 적용됨 (HTTP 호출이 `@Transactional` 밖에 위치)
- smoke script 자동화로 주요 검증 시나리오가 재현 가능해짐
- CLAUDE.md gitignore 수정으로 하네스 파일이 세션/환경 간 일관되게 적용됨

---

## 단계별 비교표

| 항목 | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|
| 지침 안정성 | 없음 | 있으나 git 추적 없음 | git 추적 + 구조 명확화 |
| 트랜잭션 경계 준수 | 임의 | rules로 명세 | rules 적용 확인됨 |
| 기존 서비스 보호 | 없음 | settings.json + rules | 동일 + Surgical Changes 원칙 |
| 검증 자동화 | 없음 | CI Gate (빌드/테스트) | CI Gate + smoke script |
| 배포 검증 범위 | 없음 | Minikube 수동 검증 | smoke script 3개 시나리오 자동화 |
| address client | 없음 | 없음 | stub/http 설정 분리, mock 서버 |
| 피드백 루프 | 없음 | feedback-log (수동) | feedback-log + smoke 결과 연결 |
| 역할 분리 | 없음 | 4개 agents | 동일 + orchestrator skill |

---

## 생산성 측정 지표

하네스 효과 측정에 사용하는 지표 정의입니다.

| 지표 | 유형 |
|---|---|
| 작업 완료 시간 | 주요 (정량, 현재 기록 미비) |
| 재작업 횟수 | 주요 (feedback-log로 부분 추적 가능) |
| 변경 범위 이탈 수 | 주요 (git diff 리뷰로 추적 가능) |
| 검증 실패 횟수 | 주요 (CI 결과, smoke 결과로 추적 가능) |
| 토큰 사용량 | 보조 (단독 비교는 의미 낮음) |

상세 지표 정의, 측정 방법, 해석 원칙은
`docs/blog-materials/claude-harness-productivity-metrics.md`를 참조하세요.

---

## 한계와 다음 단계

### 현재 한계

1. **Before 데이터 부재** — Phase 1 작업 기록이 없어 Before/After 정량 비교 불가
2. **단일 개발자, 단일 프로젝트** — 통제 그룹 없는 비교
3. **CI 범위** — logistics-api 이외 서비스 테스트 job 미연결
4. **피드백 루프** — CI 실패 원인과 feedback-log가 자동 연결되지 않음

### 다음 개선 우선순위

1. **기존 서비스(order-api 등) CI 테스트 연결** — 횡단 보호 강화
2. **smoke 결과 보존** — 실행 결과를 파일로 저장하여 이력 비교 가능하게 구성
3. **payload 유효성 검증** — null orderId로 배송 생성 방지
4. **Testcontainers 통합 테스트** — DB/Kafka 실제 동작 검증
5. **작업 시간 기록 도입** — 세션 시작/종료 시각을 feedback-log에 함께 기록
