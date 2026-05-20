# 🚀 sparta-msa-final-project (E-Commerce)

## 1. 프로젝트 개요

본 프로젝트는 **MSA 아키텍처** 환경에서 이커머스의 핵심인 **데이터 정합성과 일관성**을 보장하기 위한 분산 시스템 설계 및 고도화를 목표로 합니다. 특히 **Order-Inventory Saga** 구현 시 발생하는 병목 현상을 진단하고, 주문 처리 비동기 전환 및 상품 검색 ElasticSearch 도입을 통해 시스템 성능을 개선하는 실전적 해결 과정을 포함합니다.

- **핵심 목표**: 분산 트랜잭션 하에서의 TPS 최적화, 멱등성 보장, 데이터 스냅샷 유지, 검색 성능 고도화.
- **해결 과제 1**: 외부 시스템 통신 시 DB 커넥션 점유 문제를 해결하여 주문 처리 가용성 극대화.
- **해결 과제 2**: LIKE 풀스캔 기반 검색의 한계를 단계적으로 개선하여 고부하 환경에서도 안정적인 검색 제공.

---

## 2. 서비스 구성 (MSA)

- **product-api**: 상품 메타데이터 및 SKU(Variant) 관리, ES 검색 인덱스 동기화.
- **order-api**: 주문 생성, Saga 상태 관리 및 이력 보존.
- **inventory-api**: 재고 점유(Reservation) 및 결제 대기 TTL 관리.
- **payment-api**: 결제 처리(약식).

---

## 3. 기술 스택

- **Backend**: Spring Boot 3.x, Spring Data JPA, Hibernate, QueryDSL, Spring Data Elasticsearch
- **DB & Storage**: PostgreSQL (JSONB, GIN Index, LIST Partitioning), MinIO (S3 compatible)
- **Search**: Elasticsearch 7.17
- **Messaging**: Kafka (Choreography Saga, Transactional Outbox)
- **Infrastructure**: Kubernetes (Minikube), Docker
- **Observability**: Prometheus, Grafana, ELK Stack (Elasticsearch, Logstash, Kibana)
- **Testing**: k6 (Load Testing)

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

### 🔍 상품 검색 성능 최적화 (RDB → ElasticSearch 전환)

10만 건 데이터 기준 단계적 최적화를 통해 고부하 실패율 72% → 1.47%로 개선했습니다.

| 단계 | 고부하 실패율 | keyword p(95) |
|---|---|---|
| LIKE 기준선 | 72.33% | 6.00s |
| B-Tree 인덱스 추가 | 69.59% | 5.44s |
| FTS GIN 인덱스 | 0.00% | 41ms |
| 파티셔닝 + FTS | 29.57% | 1.87s |
| **ES 전환** | **1.47%** | **31ms** |

- FTS 고부하 실패율 0%로 성능 수치는 우수했으나, DB 커넥션 의존성·한국어 형태소 분석 한계·확장성을 고려해 ES를 최종 선택.
- Kafka Outbox 패턴을 통해 상품 생성/수정/삭제 시 ES 인덱스 자동 동기화 구현.
- ELK 스택의 ES를 로그 수집용으로 이미 운영 중이어서 추가 인프라 비용 없이 도입.

### 🛡️ 멱등성 및 정합성 보장

- **Idempotency**: `Idempotency-Key` 기반으로 중복 주문 및 중복 결제 요청 방어.
- **Snapshotting**: 상품명, 가격 등이 변동되어도 주문 시점의 데이터를 `jsonb` 형태로 영구 보관.
- **Transactional Outbox**: DB 커밋과 이벤트 발행의 원자성 보장. 최대 5회 지수 백오프 재시도, FAILED 상태 전환으로 유실 방지.

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
| **Could-Have** | 데이터 유실 방지 | DB 저장/이벤트 발행 불일치 방지를 위해 **Transactional Outbox** |

---

## 6. 성능 테스트 결과

- **테스트 환경**: Minikube (1 Node, 8GB RAM), k6 Load Test, Windows + Docker Desktop

### 주문 처리 (50 rps, 주문 완료 기준)

| 구분 | 성공률 | 비고 |
|---|---|---|
| 동기 방식 (7 rps) | 51% | HikariPool 고갈 |
| 트랜잭션 분리 (10 rps) | **100%** | 커넥션 점유 시간 감소 |
| 비동기 전환 (30 rps) | **100%** | 구조적 한계 해소 |
| 비동기 전환 (50 rps) | **100%** | 4,557건 전량 성공 |

### 상품 검색 저부하 (keyword 20 rps + brand 10 rps + complex 10 rps)

| 단계 | keyword p(95) | brand p(95) | complex p(95) | 실패율 |
|---|---|---|---|---|
| LIKE 기준선 | 185ms | 129ms | 93ms | 0.00% |
| 인덱스 추가 | 155ms | 96ms | 58ms | 0.00% |
| FTS GIN | 66ms | 68ms | 36ms | 0.01% |
| 파티셔닝 + FTS | 66ms | 79ms | 43ms | 0.00% |
| **ES 전환** | **59ms** | **56ms** | **57ms** | 1.70% |

### 상품 검색 고부하 (keyword 150 rps + brand 80 rps + complex 80 rps)

| 단계 | 실패율 | keyword p(95) | brand p(95) | complex p(95) |
|---|---|---|---|---|
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

### 3. 검색 엔진: FTS(PostgreSQL) vs ElasticSearch

| 비교 항목 | FTS GIN | ElasticSearch (선택) |
|:---|:---|:---|
| **고부하 실패율** | 0.00% | 1.47% |
| **DB 커넥션** | 사용 (검색 부하가 DB 전체에 영향) | 미사용 (DB와 완전 분리) |
| **한국어 형태소** | simple 딕셔너리 (미지원) | nori 플러그인 지원 |
| **고급 검색** | 유사어/오타 교정 별도 구현 필요 | 기본 제공 |
| **인프라 비용** | 추가 없음 | ELK 스택 재활용 (추가 없음) |

**판단 근거**: 성능 수치만 보면 FTS가 더 우수하나, DB 부하 분리·한국어 검색 품질·향후 확장성을 종합해 ES 선택. 이미 운영 중인 ELK 스택 재활용으로 추가 인프라 비용 없음.

---

## 8. 프로젝트 실행 방법

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
```
