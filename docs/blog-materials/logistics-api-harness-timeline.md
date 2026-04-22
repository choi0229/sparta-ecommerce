# logistics-api + Claude Code 하네스 작업 타임라인

벨로그 글 작성을 위한 작업 흐름 요약입니다.

---

## Phase 1 — Claude Code 환경 세팅

### Claude Code 설치 및 계정 전환
- npm을 통해 Claude Code CLI 설치
- Pro 계정으로 전환하여 Claude Code 사용 준비

### 개발 환경 의존성 설치
- Xcode Command Line Tools 미설치 상태로 `git diff` 실행 시 오류 발생 → 설치 진행
- Java 17 미설치 확인 → Homebrew를 통해 설치 (`brew install openjdk@17`)
- Homebrew 자체가 미설치 상태였음 → Homebrew 먼저 설치 후 진행

---

## Phase 2 — Claude Code 하네스 구조 점검 및 정비

### CLAUDE.md / rules / skills 구조 점검
- 기존 `.claude/.rules/` 경로가 Claude Code가 인식하는 `.claude/rules/` 경로와 불일치 확인
- `.claude/.rules` → `.claude/rules`로 경로 수정

### logistics-api rules 생성
- `.claude/rules/logistics-api.md` 신규 작성
- 배송 상태 전이 규칙, 멱등성 규칙, Outbox 규칙, 트랜잭션 경계 규칙, 테스트 기준 명세
- `kafka-outbox-saga.md` (기존 공통 규칙)을 참조하는 구조로 중복 최소화

### logistics-api skill 생성
- `.claude/skills/create-logistics-api/SKILL.md` 작성
- 생성 전 확인 항목, 패키지 구조, 기능 범위, 금지 사항, 후속 수정 파일 명세

---

## Phase 3 — logistics-api MVP 생성

### 1차 생성 및 코드 리뷰
- `create-logistics-api` Skill 기반으로 logistics-api 전체 코드 생성
- 생성 직후 8개 체크포인트 기준 read-only 리뷰 수행

### 1차 수정 (리뷰 반영)
- `OutboxPublisherJob` self-invocation 문제 수정
  - `markSent()` / `markFailed()`를 `OutboxEventTransactionalService` 별도 Bean으로 분리
- `OrderEventConsumer` 멱등성 구조 개선
  - 단계별 분리 로직 → 단일 `@Transactional` 메서드(`createShipmentForOrderEvent`)로 통합
- `ObjectMapperConfig` 추가 (JavaTimeModule 등록)

### 2차 생성 및 리뷰
- 10개 체크포인트 기준 재리뷰
- `OutboxPublisherJob.publish()`의 `@Transactional` 누락 재발 확인
  - PESSIMISTIC_WRITE 잠금이 활성 트랜잭션 없이 동작 불가 → `@Transactional` 복원

### 테스트 보강
- `LogisticsTransactionalServiceTest` 신규 작성
- 5개 단위 테스트로 PENDING stuck, 중복 생성, 회복 케이스 커버

### 테스트 및 빌드 통과
```
./gradlew test        → BUILD SUCCESSFUL
./gradlew bootJar     → BUILD SUCCESSFUL
```

---

## Phase 4 — 기존 order-api 연결

### order-create-event 토픽 연결 (Option A)
- order-api 이벤트 구조를 수정하지 않는 방향(Option A) 선택
- `OrderEventConsumer` 토픽명 `order-event` → `order-create-event` 변경
- `OrderCreatedPayload` 구조를 order-api 실제 이벤트와 맞춤 정렬
- `recipientName` / `recipientAddress`는 MVP에서 null 허용

### application.yml DB URL 수정
- `logistics-db:5436` (호스트 포트) → `logistics-db:5432` (컨테이너 내부 포트)로 수정

---

## Phase 5 — 인프라 파일 추가

### 인프라 파일 생성
- `deployment/infra/db/logistics-db.yaml` — StatefulSet + ClusterIP Service
- `deployment/logistics-api/deployment.yaml` — Deployment (replicas: 2)
- `deployment/logistics-api/service.yaml` — NodePort 30084
- `docker-compose.yml` — `logistics-db`, `logistics-api` 서비스 추가

### 주변 파일 업데이트
- `CLAUDE.md` — 서비스 목록에 logistics-api 추가
- `.claude/skills/deploy-api/SKILL.md` — 지원 서비스에 logistics-api 추가
- `scripts/redeploy-api.sh` — logistics-api case 추가

---

## Phase 6 — CI Gate 구성

### guardrails 스크립트 작성
- `scripts/claude-guardrails.sh` 신규 작성
- 5개 검사: .DS_Store, .env/secret, payment-api, claude-sessions, 위험 명령 패턴
- CI/로컬 모드 자동 분기 (`CI` 환경변수 기반)

### GitHub Actions CI Gate 작성
- `.github/workflows/claude-ci-gate.yml` 신규 작성
- 3단계 체인: `guardrails` → `logistics-api-test` → `logistics-api-build`
- `fetch-depth: 2`, Java 17 temurin, Gradle 캐시 설정

### guardrails 오탐 수정 (2회)
1. README.md의 위험 명령 설명 문구 오탐 → README.md와 설정 파일 제외 패턴 추가
2. `docs/claude-harness-evaluation.md`의 평가 문구 오탐 → 모든 `.md` 파일 제외 패턴으로 확장

---

## Phase 7 — 권한 경계 설정

### `.claude/settings.json` 생성
- `allow`: 파일 읽기, git 조회, gradlew 테스트/빌드, guardrails 실행
- `deny`: `rm -rf`, `kubectl delete`, `docker system prune`, SQL 파괴 명령, `.env` 읽기
- 나머지(docker build, kubectl apply, redeploy-api.sh 등): 사용자 승인 프롬프트

---

## Phase 8 — 평가 문서 작성

- `docs/claude-feedback-log.md` — 발견 문제 7개, 수정 내용, 재발 방지 기록
- `docs/claude-harness-evaluation.md` — 10개 항목 품질 평가 (평균 3.8/5)

---

## Phase 9 — Minikube 배포 검증

### 환경 이슈 해결
- Docker Desktop 무한 로딩 → 재시작 후 정상화
- `openjdk:17-jdk-slim` 이미지 Minikube 내 not found → Dockerfile base image 수정

### 배포 순서
1. namespace, ConfigMap, Secret 적용 확인
2. Kafka, logistics-db StatefulSet Ready 확인
3. `kubectl apply -f deployment/logistics-api/`
4. `./scripts/redeploy-api.sh logistics-api`
5. Pod 2개 Running 확인

### API 검증
- NodePort 30084 직접 접근 불가 (Docker Desktop Minikube 환경 특성)
- `kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce` 로 우회
- `/actuator/health` 200 OK, DB UP 확인
- 배송 생성 → 조회 → 상태 전이 4단계 → 잘못된 전이 400 오류 전 항목 검증 통과
