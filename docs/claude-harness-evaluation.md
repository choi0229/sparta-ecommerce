# Claude Code 하네스 품질 평가

평가 기준일: 2026-04-21
대상 브랜치: dev-logitical

---

## 평가 요약

| 항목 | 점수 | 상태 |
|---|---|---|
| CLAUDE.md 전역 규칙 | 4 / 5 | 양호 |
| path-scoped rules | 4 / 5 | 양호 |
| skills 분리 | 4 / 5 | 양호 |
| Kafka/Outbox/Saga 공통 규칙 단일화 | 5 / 5 | 완비 |
| permissions boundary | 3 / 5 | 개선 필요 |
| guardrails script | 3 / 5 | 개선 필요 |
| GitHub Actions CI Gate | 4 / 5 | 양호 |
| 테스트/빌드 검증 | 3 / 5 | 개선 필요 |
| 기존 서비스 보호 | 4 / 5 | 양호 |
| 피드백 루프 | 3 / 5 | 개선 필요 |

**전체 평균: 3.7 / 5**

---

## 항목별 평가

### 1. CLAUDE.md 전역 규칙 — 4 / 5

**근거**
- 서비스별 책임 경계(도메인 소유, DB 직접 접근 금지, 이벤트 기반 통신)가 명확히 기술됨
- `@Transactional` 내 외부 호출 금지, 스냅샷 저장, 예약-보상 흐름 등 핵심 원칙 포함
- 작업 방식(리팩토링 금지, 엔티티 직접 노출 금지, 생성자 주입)과 테스트/배포 절차 문서화
- logistics-api가 서비스 목록에 추가됨

**남은 개선점**
- 서비스별 포트 번호, DB 이름, Kafka 토픽 이름 등 빠르게 참조할 수 있는 레퍼런스 테이블 부재
- `payment-api`를 만들지 않는 이유, `product-api` 내 약식 결제 흐름의 범위가 암묵적임

---

### 2. path-scoped rules — 4 / 5

**근거**
- `kafka-outbox-saga.md`: Kafka 이벤트, Outbox, processed_event, Saga, 트랜잭션 경계 규칙을 서비스 횡단으로 단일화
- `logistics-api.md`: 배송 상태 전이, 멱등성, Outbox, 트랜잭션, 이력 규칙을 logistics-api 전용으로 명세
- `performance-observability.md`: 성능 수치 미검증 주장 금지, 측정 근거 요구 기준 포함
- rules 파일이 코드와 함께 버전 관리되어 팀 공유 가능

**남은 개선점**
- `order-api`, `product-api`, `inventory-api` 전용 path-scoped rule 부재
- rules 파일이 어떤 glob 경로에서 자동 로드되는지 명세가 없어 로드 누락 가능성 존재

---

### 3. skills 분리 — 4 / 5

**근거**
- `create-logistics-api`: 생성 전 확인 항목, 원칙, 패키지 구조, 생성 범위, 금지 사항, 후속 수정 파일을 구체적으로 명세
- `deploy-api`: 지원 서비스 목록(logistics-api 포함), 예시 명령어 포함
- `java-coding`: Java 코딩 스타일 규칙 별도 분리
- Skill 단위로 활성화되므로 불필요한 컨텍스트 로드를 방지

**남은 개선점**
- `create-logistics-api` skill이 단일 서비스 생성에 특화되어 있어 다른 서비스(예: notification-api) 생성 시 재사용하기 위해 별도 skill 작성 필요
- skill 간 공통 원칙(트랜잭션, 멱등성)이 중복될 수 있어 rules 파일 참조 형태로 정리하면 유지보수 용이

---

### 4. Kafka/Outbox/Saga 공통 규칙 단일화 — 5 / 5

**근거**
- `kafka-outbox-saga.md` 한 파일에 이벤트 원칙, 이벤트 계약, processed_event, Outbox, Saga/보상, 트랜잭션 경계, 테스트 규칙을 모두 포함
- 중복 정의 없이 logistics-api rule이 이 파일을 명시적으로 참조하는 구조
- `@Transactional` 내 금지 외부 호출 목록이 구체적 (Kafka request-reply, HTTP, Elasticsearch, blocking 작업)
- 보상 처리 멱등성, Consumer 재시작 후 재처리 등 실제 장애 시나리오까지 포함

**남은 개선점**
- 특별히 없음. 단, 실제 장애 발생 시 규칙에 따른 보완 여부를 주기적으로 재검토 권장

---

### 5. permissions boundary — 3 / 5

**근거**
- `allow`: 파일 읽기, git 조회, gradlew test/build, guardrails 실행을 명시적으로 허용
- `deny`: `rm -rf`, `kubectl delete`, `docker system prune`, SQL 파괴 명령, `.env` 읽기를 차단
- `docker build`, `kubectl apply`, `redeploy-api.sh` 등 배포 명령은 allow/deny 어디에도 없어 승인 프롬프트 표시

**남은 개선점**
- `settings.json`의 glob 패턴이 bash 패턴과 다르게 동작할 수 있어 실제 차단 여부를 각 명령으로 직접 확인 필요
- `Bash(rm -r *)` 패턴이 `rm -r ./somedir` 형태를 모두 잡는지 edge case 검증 필요
- `settings.local.json` 오버라이드로 deny 규칙이 무력화될 수 있음 — 팀 내 로컬 설정 관리 기준 부재
- SQL 파괴 명령 패턴이 Bash 명령 중 psql 인터랙티브 세션 내 입력은 탐지하지 못함

---

### 6. guardrails script — 3 / 5

**근거**
- 5개 검사 항목(.DS_Store, .env/secret, payment-api, claude-sessions, 위험 명령)을 체계적으로 구성
- CI/로컬 모드 자동 분기(`CI` 환경변수 기반)
- `set -euo pipefail` 적용으로 예상치 못한 오류 시 즉시 중단
- README·설정 파일 오탐 문제를 제외 패턴으로 해결

**남은 개선점**
- `grep -P`(Perl regex)는 macOS 기본 grep에서 지원되지 않아 로컬 실행 시 실패 가능 — `grep -E` 또는 `perl -ne` 대체 권장
- 위험 패턴이 파일 내용이 아닌 파일명으로만 감지되는 항목(1~4번)과 내용 기반 항목(5번)이 혼재하여 로직 가독성 저하
- 이진 파일(이미지, jar, class)이 변경 목록에 포함될 경우 `grep -lP`가 경고를 출력할 수 있음
- 제외 경로 목록이 스크립트 본문에 하드코딩되어 있어 신규 문서 추가 시 매번 수동 업데이트 필요

---

### 7. GitHub Actions CI Gate — 4 / 5

**근거**
- `guardrails → logistics-api-test → logistics-api-build` 3단계 체인으로 빠른 실패(fast-fail) 구조
- `fetch-depth: 2`로 `HEAD~1` diff 가용성 보장
- Java 17 + temurin + `cache: gradle`로 빌드 캐시 효율화
- 테스트 결과를 `actions/upload-artifact`로 보존
- push/PR 양쪽 트리거 적용

**남은 개선점**
- 현재 logistics-api만 CI 대상 — order-api, inventory-api, product-api 테스트 job 부재
- 테스트 실패 시 Slack/이메일 알림 미설정
- `bootJar` 빌드 결과물을 아티팩트로 보존하지 않아 배포 파이프라인 연결 불가

---

### 8. 테스트/빌드 검증 — 3 / 5

**근거**
- `LogisticsTransactionalServiceTest`: 5개 단위 테스트, DB 불필요, `@ExtendWith(MockitoExtension.class)`
- 멱등성 4개 케이스 + 중복 orderId 케이스 커버
- `ReflectionTestUtils.setField()`로 private id 주입하여 NPE 회피

**남은 개선점**
- `OutboxPublisherJob`, `OutboxEventTransactionalService` 단위 테스트 부재
- `OrderEventConsumer` 단위 테스트 부재 (payload 역직렬화, 정상/비정상 JSON 케이스)
- 상태 전이 유효성 검증(`Shipment.transitionTo()`) 단위 테스트 부재
- 통합 테스트 전무 — Testcontainers 기반 PostgreSQL + Kafka 통합 테스트 미구성
- 다른 서비스(order-api, inventory-api, product-api) CI 테스트 미연결

---

### 9. 기존 서비스 보호 — 4 / 5

**근거**
- logistics-api가 order-api/inventory-api/product-api DB에 직접 접근하는 코드 없음
- 이벤트 페이로드를 `OrderCreatedPayload` 내부 record로 격리하여 order-api 코드 의존성 없음
- `order-create-event` 토픽 이름을 맞춰 기존 order-api 코드 수정 없이 연결
- CI 체인이 logistics-api만 대상이므로 기존 서비스 빌드에 영향 없음

**남은 개선점**
- `order-create-event` payload 구조가 변경될 경우 logistics-api가 조용히 null orderId로 배송 생성할 수 있음 — payload 유효성 검증 부재
- 기존 서비스 변경이 logistics-api에 미치는 영향을 감지할 cross-service 컨트랙트 테스트 미구성

---

### 10. 피드백 루프 — 3 / 5

**근거**
- 코드 리뷰 → 수정 → 테스트 → 재리뷰 사이클을 이번 작업에서 2회 반복
- `docs/claude-feedback-log.md`로 발견 사항과 수정 이유를 추적 가능하게 기록
- guardrails가 오탐을 즉시 감지하여 빠른 수정으로 이어짐

**남은 개선점**
- 피드백 로그 작성이 수동 — CI 실패 원인이 자동으로 로그에 연결되지 않음
- 이번 작업에서 발견된 문제들이 rules 파일에 반영되었는지 확인하는 검토 프로세스 미정립
- 다음 작업 전 하네스 평가 점수를 재측정하는 주기적 리뷰 주기 미설정
- 팀원 간 피드백 로그 공유 및 리뷰 프로세스 부재

---

## 다음 개선 우선순위

1. **`grep -P` → `grep -E` 또는 `perl` 대체** — macOS 로컬 호환성 문제, 즉시 수정 가능
2. **OrderEventConsumer · OutboxPublisherJob 단위 테스트 추가** — 현재 커버리지 공백 중 위험도 높음
3. **payload 유효성 검증** — null orderId로 배송 생성 방지
4. **기존 서비스(order-api 등) CI 테스트 연결** — 횡단 보호 강화
5. **Testcontainers 통합 테스트** — DB/Kafka 실제 동작 검증
