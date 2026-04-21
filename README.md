# 🚀 sparta-msa-final-project (E-Commerce)

## 1. 프로젝트 개요

본 프로젝트는 **MSA 아키텍처** 환경에서 이커머스의 핵심인 **데이터 정합성과 일관성**을 보장하기 위한 분산 시스템 설계 및 고도화를 목표로 합니다. 특히 **Order-Inventory Saga** 구현 시 발생하는 병목 현상을 진단하고, 주문 처리 비동기 전환 및 상품 검색 Elasticsearch 도입을 통해 시스템 성능을 개선하는 실전적 해결 과정을 포함합니다.

또한 Claude Code를 활용한 AI Native 개발 흐름을 실험하기 위해, 단순 프롬프트 기반 개발이 아니라 **Rules, Skills, Guardrails, CI Gate**를 포함한 Claude Code 하네스를 구성하고, 이를 기반으로 신규 `logistics-api`를 추가했습니다.

- **핵심 목표**: 분산 트랜잭션 하에서의 TPS 최적화, 멱등성 보장, 데이터 스냅샷 유지, 검색 성능 고도화, AI Agent 기반 개발 흐름 검증.
- **해결 과제 1**: 외부 시스템 통신 시 DB 커넥션 점유 문제를 해결하여 주문 처리 가용성 극대화.
- **해결 과제 2**: LIKE 풀스캔 기반 검색의 한계를 단계적으로 개선하여 고부하 환경에서도 안정적인 검색 제공.
- **해결 과제 3**: 주문 이후 배송 도메인을 `logistics-api`로 분리하고, 이벤트 기반으로 배송 요청과 배송 상태를 관리.
- **해결 과제 4**: Claude Code가 프로젝트 규칙 안에서 안전하게 작업하도록 하네스와 CI Gate 구성.

---

## 2. 서비스 구성 (MSA)

- **product-api**: 상품 메타데이터 및 SKU(Variant) 관리, ES 검색 인덱스 동기화, 약식 결제 흐름 포함.
- **order-api**: 주문 생성, 주문 상태 관리, Saga 상태 관리 및 주문 이력 보존.
- **inventory-api**: 재고 예약(Reservation), 결제 대기 TTL 만료, 재고 해제 및 보상 처리.
- **logistics-api**: 배송 요청 생성, 배송 상태 관리, 배송 상태 이력 저장, 주문 이벤트 수신 및 배송 이벤트 발행.
- **payment-api**: 독립 서비스가 아닌 `product-api` 내부에 약식 구현된 결제 흐름.

---

## 3. 기술 스택

- **Backend**: Spring Boot 3.x, Spring Data JPA, Hibernate, QueryDSL, Spring Data Elasticsearch
- **DB & Storage**: PostgreSQL (JSONB, GIN Index, LIST Partitioning), MinIO (S3 compatible)
- **Search**: Elasticsearch 7.17
- **Messaging**: Kafka (Choreography Saga, Transactional Outbox)
- **Infrastructure**: Kubernetes (Minikube), Docker, Docker Compose
- **Observability**: Prometheus, Grafana, ELK Stack (Elasticsearch, Logstash, Kibana)
- **Testing**: JUnit5, Mockito, k6 Load Testing
- **AI Native Development**: Claude Code, Claude Code Rules, Skills, Guardrails, GitHub Actions CI Gate

---

## 4. 핵심 고도화 내용

### 🔄 주문 처리 최적화 (동기 → 비동기 전환)

Kafka를 통한 상품 정보 획득 시 발생하던 **DB 커넥션 고갈 문제**를 단계적으로 해결했습니다.

**1단계: 트랜잭션 분리**

- `OrderService`(Kafka 이벤트 전송)와 `OrderTransactionalService`(`@Transactional` DB 작업)로 분리하여 Kafka 대기 구간 동안 DB 커넥션을 점유하지 않도록 개선.
- 동기 방식 기준 10 rps까지 안정화 달성.

**2단계: 비동기 전환 (202 Accepted + 폴링)**

- 트랜잭션 분리만으로는 고부하에서 스레드 블로킹 문제가 재현되어 구조 자체를 전환.
- `POST /api/orders` → 즉시 202 Accepted 반환, Outbox → Kafka → Consumer → DB 저장 비동기 처리.
- `GET /api/orders/status/{idemKey}` 폴링으로 처리 결과 확인.
- in-memory `ConcurrentHashMap` 기반 `ProductSnapshotPendingStore` 제거 → 레플리카 간 응답 유실 문제 해결 및 수평 확장 가능.

---

### 🔍 상품 검색 성능 최적화 (RDB → Elasticsearch 전환)

10만 건 데이터 기준 단계적 최적화를 통해 고부하 실패율 72% → 1.47%로 개선했습니다.

| 단계 | 고부하 실패율 | keyword p(95) |
|---|---:|---:|
| LIKE 기준선 | 72.33% | 6.00s |
| B-Tree 인덱스 추가 | 69.59% | 5.44s |
| FTS GIN 인덱스 | 0.00% | 41ms |
| 파티셔닝 + FTS | 29.57% | 1.87s |
| **ES 전환** | **1.47%** | **31ms** |

- FTS 고부하 실패율 0%로 성능 수치는 우수했으나, DB 커넥션 의존성·한국어 형태소 분석 한계·확장성을 고려해 ES를 최종 선택.
- Kafka Outbox 패턴을 통해 상품 생성/수정/삭제 시 ES 인덱스 자동 동기화 구현.
- ELK 스택의 ES를 로그 수집용으로 이미 운영 중이어서 추가 인프라 비용 없이 도입.

---

### 🛡️ 멱등성 및 정합성 보장

- **Idempotency**: `Idempotency-Key` 기반으로 중복 주문 및 중복 결제 요청 방어.
- **Snapshotting**: 상품명, 가격 등이 변동되어도 주문 시점의 데이터를 `jsonb` 형태로 영구 보관.
- **Transactional Outbox**: DB 커밋과 이벤트 발행의 원자성 보장. 최대 5회 지수 백오프 재시도, FAILED 상태 전환으로 유실 방지.
- **processed event / idempotency record**: Kafka 이벤트 중복 전달 상황에서도 중복 주문, 중복 재고 처리, 중복 배송 생성이 발생하지 않도록 방어.

---

### 🚚 logistics-api 추가: 주문 이후 배송 도메인 분리

주문 이후의 배송 요청 생성과 배송 상태 관리를 담당하는 `logistics-api`를 신규 서비스로 추가했습니다.

`logistics-api`는 배송/물류 도메인의 상태만 소유하며, `order-api`, `product-api`, `inventory-api`의 DB에 직접 접근하지 않습니다. 주문 생성 정보는 Kafka 이벤트를 통해 수신하고, 배송 상태 변경 결과는 Transactional Outbox 기반으로 이벤트 발행합니다.

주요 구현 내용은 다음과 같습니다.

- `order-create-event` 수신 기반 배송 요청 생성
- `Shipment`, `ShipmentStatusHistory`, `IdempotencyRecord`, `OutboxEvent` 도메인 모델 추가
- 배송 상태 전이 검증
- 배송 상태 이력 append-only 저장
- `orderId` unique 제약을 통한 중복 배송 생성 방지
- Kafka 이벤트 중복 수신에 대비한 eventId 기반 멱등성 처리
- 배송 상태 변경과 이벤트 발행의 정합성을 위한 Transactional Outbox 적용

배송 상태는 다음과 같이 관리합니다.

```text
READY      → SHIPPED, CANCELED
SHIPPED    → IN_TRANSIT, FAILED, CANCELED
IN_TRANSIT → DELIVERED, FAILED
DELIVERED  → 변경 불가
FAILED     → 변경 불가
CANCELED   → 변경 불가
```

초기 MVP에서는 현재 `order-api`의 이벤트 구조를 유지하기 위해 배송지 정보(`recipientName`, `recipientAddress`)는 `null` 허용으로 처리했습니다. 이후 주문 이벤트 확장 또는 별도 배송지 업데이트 API를 통해 보강할 수 있습니다.

---

## 5. 개발 기능 정의 (MVP Status)

| 구분 | 구현 내용 | 상세 설명 |
|---|---|---|
| **Must-Have** | 상세한 상품 옵션 관리 | 색상, 사이즈 등 복잡한 옵션을 **SKU(Variant) 단위**로 관리 |
| | 안전한 주문-재고 연동 | 주문 → 재고 **예약/점유**, 실패 시 **Saga 보상/실패 처리** |
| | 주문 당시 정보 보존 | 상품 가격/옵션 변경에도 **주문 당시 정보 Snapshot**을 주문 item에 박제 |
| | Tree 구조 카테고리 | Tree 구조의 카테고리 CRUD 구현 |
| | 상품 검색 | LIKE → FTS → ES 단계적 최적화, ES Outbox 동기화 |
| **Should-Have** | 중복 주문/결제 방지 | 동일 요청/이벤트 중복에도 **1회만 처리(멱등성)** |
| | 결제 대기 재고 자동 회수 | 결제 이탈 시 **TTL(expires_at) + 스케줄러**로 자동 해제 |
| | 주문 단계 실시간 추적 | Saga 진행 단계를 **Saga 상태 테이블**로 기록/추적 |
| | 배송 요청 생성 | 주문 생성 이벤트(`order-create-event`)를 수신해 배송 요청 자동 생성 |
| | 배송 상태 관리 | READY, SHIPPED, IN_TRANSIT, DELIVERED, FAILED, CANCELED 상태 전이 관리 |
| | 배송 이력 저장 | 상태 변경 이력을 append-only 방식으로 저장 |
| | 배송 이벤트 발행 | 배송 상태 변경 시 Outbox 기반으로 `shipment-event` 발행 |
| **Could-Have** | 데이터 유실 방지 | DB 저장/이벤트 발행 불일치 방지를 위해 **Transactional Outbox** |

---

## 6. 성능 테스트 결과

- **테스트 환경**: Minikube (1 Node, 8GB RAM), k6 Load Test, Windows + Docker Desktop

### 주문 처리 (50 rps, 주문 완료 기준)

| 구분 | 성공률 | 비고 |
|---|---:|---|
| 동기 방식 (7 rps) | 51% | HikariPool 고갈 |
| 트랜잭션 분리 (10 rps) | **100%** | 커넥션 점유 시간 감소 |
| 비동기 전환 (30 rps) | **100%** | 구조적 한계 해소 |
| 비동기 전환 (50 rps) | **100%** | 4,557건 전량 성공 |

### 상품 검색 저부하 (keyword 20 rps + brand 10 rps + complex 10 rps)

| 단계 | keyword p(95) | brand p(95) | complex p(95) | 실패율 |
|---|---:|---:|---:|---:|
| LIKE 기준선 | 185ms | 129ms | 93ms | 0.00% |
| 인덱스 추가 | 155ms | 96ms | 58ms | 0.00% |
| FTS GIN | 66ms | 68ms | 36ms | 0.01% |
| 파티셔닝 + FTS | 66ms | 79ms | 43ms | 0.00% |
| **ES 전환** | **59ms** | **56ms** | **57ms** | 1.70% |

### 상품 검색 고부하 (keyword 150 rps + brand 80 rps + complex 80 rps)

| 단계 | 실패율 | keyword p(95) | brand p(95) | complex p(95) |
|---|---:|---:|---:|---:|
| LIKE 기준선 | 72.33% | 6.00s | 5.64s | 5.41s |
| 인덱스 추가 | 69.59% | 5.44s | 5.00s | 4.70s |
| FTS GIN | **0.00%** | **41ms** | **21ms** | **14ms** |
| 파티셔닝 + FTS | 29.57% | 1.87s | 1.99s | 1.72s |
| **ES 전환** | **1.47%** | **31ms** | **21ms** | **26ms** |

---

## 7. 아키텍처 결정 이력 (Architecture Decision Record)

### 1. 상품 정보 확보 전략: 직접 조회(A) vs Read Model(B)

| 비교 항목 | A안: Product 서비스 직접 조회 (선택) | B안: Order 내 Read Model 유지 (보류) |
|:---|:---|:---|
| **동작 방식** | 주문 시점에 Product에서 최신 정보 조회 후 Snapshot 저장 | Product 변경 이벤트를 구독하여 Order DB에 복제본 유지 |
| **데이터 정합성** | 강한 일관성(Strong Consistency) | 결과적 일관성(Eventual Consistency), 반영 지연 가능 |
| **시스템 결합도** | 두 서비스 간 런타임 의존성 존재 | 런타임 독립성 확보 |
| **운영 복잡도** | 단순(조회 실패/타임아웃 중심) | Outbox/DLQ/Replay 등 운영 복잡도 상승 |

**판단 근거**: 스냅샷은 "결제 순간의 정확한 값"을 박제하는 기능이므로 SoT인 Product 서비스에서 직접 조회하는 A안 선택. SKU 단건 반복 조회가 아닌 List 기반 Bulk 조회로 I/O 최소화.

---

### 2. 주문 처리 모델: 동기(Blocking) → 비동기(Non-blocking) 전환

| 비교 항목 | 동기식 (초기 구현) | 비동기식 (최종 선택) |
|:---|:---|:---|
| **응답성** | 즉각적인 성공/실패 판단 | 202 Accepted + 폴링 |
| **자원 사용** | Kafka 대기 중 스레드/커넥션 점유 | 자원 즉시 반환 |
| **수평 확장** | in-memory 상태로 레플리카 확장 불가 | Outbox 기반으로 레플리카 자유롭게 확장 |
| **구현 난이도** | 단순 | Correlation ID, 폴링 API 추가 필요 |

**판단 근거**: 트랜잭션 분리로 10 rps까지는 안정화했으나 그 이상에서 스레드 블로킹 문제 재현. Redis 기반 공유 상태 저장도 검토했으나 블로킹 구조 자체는 해결 불가. 비동기 전환으로 커넥션 고갈과 레플리카 문제를 동시에 해결.

---

### 3. 검색 엔진: FTS(PostgreSQL) vs Elasticsearch

| 비교 항목 | FTS GIN | Elasticsearch (선택) |
|:---|:---|:---|
| **고부하 실패율** | 0.00% | 1.47% |
| **DB 커넥션** | 사용 (검색 부하가 DB 전체에 영향) | 미사용 (DB와 완전 분리) |
| **한국어 형태소** | simple 딕셔너리 (미지원) | nori 플러그인 지원 |
| **고급 검색** | 유사어/오타 교정 별도 구현 필요 | 기본 제공 |
| **인프라 비용** | 추가 없음 | ELK 스택 재활용 (추가 없음) |

**판단 근거**: 성능 수치만 보면 FTS가 더 우수하나, DB 부하 분리·한국어 검색 품질·향후 확장성을 종합해 ES 선택. 이미 운영 중인 ELK 스택 재활용으로 추가 인프라 비용 없음.

---

### 4. logistics-api 통합 전략: order-api 변경(B) vs logistics-api 적응(A)

| 비교 항목 | A안: logistics-api가 기존 order 이벤트에 맞춤 (선택) | B안: order-api 이벤트 구조 확장 |
|:---|:---|:---|
| **동작 방식** | 기존 `order-create-event`를 logistics-api가 수신 | 주문 요청/엔티티/이벤트에 배송지 정보 추가 |
| **기존 서비스 영향** | order-api 수정 없음 | order-api DTO, Entity, Event, Migration 수정 필요 |
| **구현 범위** | logistics-api 내부 수정 중심 | order-api와 logistics-api 동시 수정 |
| **배송지 정보** | MVP에서는 null 허용, 추후 보강 | 주문 생성 시점부터 배송지 정보 포함 |
| **리스크** | 배송지 정보 보강 필요 | 주문 도메인 변경 범위 증가 |

**판단 근거**: 초기 목표는 `logistics-api`를 독립 서비스로 추가하고 기존 주문 흐름을 깨지 않는 것이었습니다. 따라서 1차 MVP에서는 `logistics-api`가 현재 `order-api`의 `order-create-event` 구조에 맞추는 A안을 선택했습니다. 배송지 정보는 이후 주문 이벤트 확장 또는 별도 배송지 업데이트 API로 보강할 수 있도록 열어두었습니다.

---

### 5. AI Native 개발 방식: 단순 프롬프트 사용 vs Claude Code 하네스 구성

| 비교 항목 | 단순 프롬프트 기반 개발 | Claude Code 하네스 기반 개발 (선택) |
|:---|:---|:---|
| **작업 방식** | 매번 자연어로 요청 | `CLAUDE.md`, rules, skills, guardrails 기반 작업 |
| **규칙 일관성** | 세션마다 흔들릴 수 있음 | 서비스별 rules와 공통 rules로 일관성 유지 |
| **작업 범위 통제** | 명시하지 않으면 기존 코드까지 수정 가능 | path-scoped rules와 금지 조건으로 범위 제한 |
| **반복 작업** | 매번 절차 설명 필요 | Skill로 배포/신규 API 생성 절차 재사용 |
| **검증 방식** | 수동 테스트 중심 | Guardrails + GitHub Actions CI Gate로 자동 검증 |

**판단 근거**: Claude Code를 단순 코드 생성 도구로 사용하지 않고, 프로젝트의 아키텍처 원칙과 운영 절차 안에서 통제하기 위해 하네스 구조를 먼저 구성했습니다. 이를 통해 신규 `logistics-api` 생성 과정에서도 기존 `product-api`, `order-api`, `inventory-api` 코드를 수정하지 않고 독립 서비스를 추가할 수 있었습니다.

---

## 8. Claude Code 하네스 및 CI Gate

본 프로젝트는 Claude Code를 활용한 AI Native 개발 흐름을 실험하기 위해 Claude Code 하네스를 구성했습니다.

하네스의 목적은 AI Agent가 프로젝트 구조를 임의로 변경하지 않고, 정해진 도메인 경계와 검증 절차 안에서 작업하도록 제한하는 것입니다.

### 하네스 구성

```text
.claude/
├── rules/
│   ├── product-api.md
│   ├── order-api.md
│   ├── inventory-api.md
│   ├── logistics-api.md
│   ├── kafka-outbox-saga.md
│   └── performance-observability.md
└── skills/
    ├── deploy-api/
    │   └── SKILL.md
    ├── create-logistics-api/
    │   └── SKILL.md
    └── java-coding/
        ├── SKILL.md
        └── references/
            └── querydsl.md
```

### 설계 원칙

- `CLAUDE.md`에는 전체 프로젝트의 핵심 원칙만 유지
- 서비스별 작업 규칙은 `.claude/rules/*.md`로 분리
- Kafka, Outbox, Saga, 멱등성 규칙은 `kafka-outbox-saga.md`에서 공통 관리
- 배포 절차는 `deploy-api` Skill과 `scripts/redeploy-api.sh`를 단일 출처로 관리
- 신규 `logistics-api` 생성 절차는 `create-logistics-api` Skill로 분리
- 성능/관측성 rule은 일반 비즈니스 로직 작업 시 자동 로드되지 않도록 paths 범위 축소

### CI Gate

Claude Code가 생성한 변경사항을 검증하기 위해 GitHub Actions 기반 CI Gate를 추가했습니다.

```text
.github/workflows/claude-ci-gate.yml
scripts/claude-guardrails.sh
```

CI Gate는 다음 단계를 수행합니다.

1. `scripts/claude-guardrails.sh` 실행
2. 위험 파일 및 금지 명령어 포함 여부 검사
3. `logistics-api` 테스트 실행
4. `logistics-api` bootJar 빌드 실행

Guardrails 검사 항목은 다음과 같습니다.

- `.DS_Store` 포함 여부
- `.env`, `.env.*`, `secrets/` 포함 여부
- 의도하지 않은 `payment-api` 디렉터리 생성 여부
- Claude Code 세션 로그 커밋 여부
- 위험 명령 문자열 포함 여부
  - `rm -rf`
  - `docker system prune`
  - `kubectl delete`
  - `DROP TABLE`
  - `TRUNCATE`

이를 통해 AI Agent가 생성한 코드가 최소한의 안전 검증을 통과한 뒤 커밋/PR에 포함되도록 구성했습니다.

### 검증 결과

`logistics-api` 생성 후 다음 검증을 통과했습니다.

```bash
cd logistics-api
./gradlew test
./gradlew bootJar -x test
```

```text
BUILD SUCCESSFUL
```

GitHub Actions에서 `Claude CI Gate` workflow가 정상 실행되는 것을 확인했습니다.

Minikube 클러스터에서도 배포 및 API 동작을 검증했습니다.

```bash
kubectl get pod -n ecommerce -l app=logistics-api
# NAME                            READY   STATUS    RESTARTS   AGE
# logistics-api-xxxxxxxxx-xxxxx   2/2     Running   0          ...
# logistics-api-xxxxxxxxx-xxxxx   2/2     Running   0          ...
```

```bash
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce
curl http://localhost:8084/actuator/health
# {"status":"UP", ...}  — DB health UP 포함
```

| API | 결과 |
|---|---|
| `POST /shipments` | 201 Created, `status: READY` |
| `GET /shipments/1` | 200 OK |
| `PATCH /shipments/1/status` (READY→SHIPPED) | 200 OK |
| `PATCH /shipments/1/status` (SHIPPED→IN_TRANSIT) | 200 OK |
| `PATCH /shipments/1/status` (IN_TRANSIT→DELIVERED) | 200 OK |
| `PATCH /shipments/1/status` (DELIVERED→FAILED) | 400 Bad Request, `errorCode: INVALID_SHIPMENT_STATUS_TRANSITION` |

NodePort 30084는 Docker Desktop 기반 Minikube 환경에서 직접 접근이 되지 않아 `kubectl port-forward`로 검증했습니다. Service와 Endpoint는 정상적으로 구성되어 있으며, 이는 서비스/Pod 문제가 아니라 로컬 Minikube 외부 접근 방식의 차이입니다.

---

## 9. 프로젝트 실행 방법

### 인프라 가동

```powershell
minikube start
minikube tunnel # LoadBalancer 서비스 노출을 위해 필수
kubectl apply -f deployment/
```

### 포트 확인

```powershell
minikube service product-api-svc -n ecommerce --url
minikube service order-api-svc -n ecommerce --url
minikube service inventory-api-svc -n ecommerce --url
minikube service logistics-api-svc -n ecommerce --url
```

### logistics-api 단독 테스트 및 빌드

```bash
cd logistics-api
./gradlew test
./gradlew bootJar -x test
```

### 서비스 재배포

실행 중인 Minikube/Kubernetes 환경에 특정 API 변경사항을 반영할 때는 다음 스크립트를 사용합니다.

```bash
./scripts/redeploy-api.sh product-api
./scripts/redeploy-api.sh order-api
./scripts/redeploy-api.sh inventory-api
./scripts/redeploy-api.sh logistics-api
```

replica 수를 직접 지정하려면 두 번째 인자로 전달합니다.

```bash
./scripts/redeploy-api.sh logistics-api 2
```

### Claude Code Guardrails 로컬 실행

커밋 전 Claude Code 하네스 검사를 로컬에서 실행할 수 있습니다.

```bash
git add <커밋할 파일>
bash scripts/claude-guardrails.sh
```

성공 시 다음 메시지가 출력됩니다.

```text
Claude guardrails passed.
```

---

## 10. 향후 개선 과제

- ~~실제 Minikube/Kubernetes 환경에서 `logistics-api` 배포 검증~~ (완료)
- `shipment-event`를 `order-api`가 수신해 주문 배송 상태에 반영하는 흐름 추가
- 배송지 정보 처리 방식 결정
  - 주문 이벤트 확장
  - 배송지 업데이트 API 추가
  - 사용자 주소 서비스 연동
- Outbox retry 정책 고도화
  - backoff
  - 최대 재시도 횟수
  - DLQ 또는 수동 재처리
- `logistics-api` 운영 지표 추가
  - 배송 생성 수
  - 배송 상태 변경 수
  - 배송 실패 수
  - Outbox 발행 실패 수
  - Kafka Consumer 처리 실패 수
- Claude Code 하네스 고도화
  - ~~`.claude/settings.json` 권한 경계 추가~~ (완료)
  - hooks 기반 자동 guardrail 추가
  - 전체 서비스 테스트 matrix CI 확장
  - ~~`docs/claude-feedback-log.md` 기반 피드백 루프 기록~~ (완료)
