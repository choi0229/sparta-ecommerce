# 🚀 sparta-msa-final-project (E-Commerce)

## 1. 프로젝트 개요
본 프로젝트는 **MSA 아키텍처** 환경에서 이커머스의 핵심인 **데이터 정합성과 일관성**을 보장하기 위한 분산 시스템 설계 및 고도화를 목표로 합니다. 특히 **Order-Inventory Saga** 구현 시 발생하는 병목 현상을 진단하고, 트랜잭션 최적화를 통해 성능을 개선하는 실전적 해결 과정을 포함합니다.

- **핵심 목표**: 분산 트랜잭션 하에서의 TPS 최적화, 멱등성 보장, 데이터 스냅샷 유지.
- **해결 과제**: 외부 시스템 통신 시 DB 커넥션 점유 문제를 해결하여 시스템 가용성 극대화.

---

## 2. 서비스 구성 (MSA)
- **product-service**: 상품 메타데이터 및 SKU(Variant) 관리.
- **order-service**: 주문 생성, Saga 상태 관리 및 이력 보존.
- **inventory-service**: 재고 점유(Reservation) 및 결제 대기 TTL 관리.

---

## 3. 기술 스택
- **Backend**: Spring Boot 3.x, Spring Data JPA, Hibernate
- **DB & Storage**: PostgreSQL (JSONB), MinIO (S3 compatible)
- **Messaging**: Kafka
- **Infrastructure**: Kubernetes (Minikube), Docker
- **Observability**: Prometheus, Grafana, ELK Stack (Elasticsearch, Logstash, Kibana)
- **Testing**: k6 (Load Testing)

---

## 4. 핵심 고도화 내용 (Saga & Performance)

### 🔄 분산 트랜잭션 최적화 (Transaction Boundary)
Kafka를 통한 상품 정보 획득(fetchBySkus) 시 발생하던 **DB 커넥션 고갈 문제**를 해결하기 위해 서비스 레이어를 분리했습니다.
- **OrderService**: 비동기 통신(Kafka) 및 응답 대기를 수행하며, `@Transactional`을 제거하여 대기 시간 동안 DB 커넥션을 점유하지 않음.
- **OrderTransactionalService**: 상품 정보 획득 후 실제 DB 쓰기 작업만 짧은 트랜잭션 범위 내에서 수행.



### 🛡️ 멱등성 및 정합성 보장
- **Idempotency**: `Idempotency-Key`를 기반으로 중복 주문 및 중복 결제 요청 방어.
- **Snapshotting**: 상품명, 가격 등이 변동되어도 주문 시점의 데이터를 `jsonb` 형태로 영구 보관.
- **Transactional Outbox**: DB 커밋과 이벤트 발행의 원자성을 보장하기 위한 Outbox 패턴 적용.

---

## 5. 개발 기능 정의 (MVP Status)

| 구분 | 구현 내용 | 상세 설명 |
|---|---|---|
| **Must-Have (핵심 기능)** | 상세한 상품 옵션 관리 | 색상, 사이즈 등 복잡한 옵션을 **SKU(Variant) 단위**로 관리 |
|  | 안전한 주문-재고 연동 | 주문 → 재고 **예약/점유**, 실패 시 **Saga 보상/실패 처리** |
|  | 주문 당시 정보 보존 | 상품 가격/옵션 변경에도 **주문 당시 정보 Snapshot**을 주문 item에 박제 |
|  | Tree 구조 카테고리 | Tree 구조의 카테고리 CRUD 구현 |
| **Should-Have (주요 기능)** | 중복 주문/결제 방지 | 동일 요청/이벤트 중복에도 **1회만 처리(멱등성)** |
|  | 결제 대기 재고 자동 회수 | 결제 이탈 시 **TTL(expires_at) + 스케줄러**로 자동 해제 |
|  | 주문 단계 실시간 추적 | 사가 진행 단계를 **Saga 상태 테이블**로 기록/추적 |
| **Could-Have (부가 기능)** | 데이터 유실 방지 | DB 저장/이벤트 발행 불일치 방지를 위해 **Transactional Outbox** |

---

## 6. 성능 테스트 결과 및 지표
- **테스트 환경**: Minikube (1 Node, 8GB), k6 Load Test
- **개선 전**: 초당 10건 미만의 부하에서도 p(95) 응답 시간이 40초를 초과하며 35% 이상의 실패율 기록.
- **개선 후**: 
  - **응답 속도**: p(95) 기준 600ms 미만 달성 (안정적 TPS 구간 내).
  - **안정성**: 빠른 실패(Fail-Fast) 전략(Kafka Timeout 300ms)으로 시스템 마비 방지.
  - **성공률**: 99.43% (네트워크 일시 지연 제외 사실상 100%).

## 7. 아키텍처 결정 이력 (Architecture Decision Record)

나는 프로젝트를 진행하며 MSA의 핵심인 **도메인 자율성**과 이커머스의 생명인 **데이터 정합성(주문 시점 정확한 값 박제)** 사이에서 최적의 균형점을 찾기 위해 다음과 같은 의사결정을 내렸다.

---

### 1. 상품 정보 확보 전략: 직접 조회(A) vs Read Model(B)

주문 시점에 상품 스냅샷(Snapshot)을 생성하기 위한 데이터 확보 방안을 비교 검토했다.

| 비교 항목 | A안: Product 서비스 직접 조회 (선택) | B안: Order 내 Read Model 유지 (보류) |
| :--- | :--- | :--- |
| **동작 방식** | 주문 시점에 Product에서 최신 정보 조회 후 Snapshot 저장 *(HTTP/gRPC 또는 Kafka request-reply)* | Product 변경 이벤트를 구독하여 Order DB에 복제본(Projection) 유지 |
| **데이터 정합성** | **강한 일관성(Strong Consistency)**: SoT 최신 값 기반 스냅샷 | **결과적 일관성(Eventual Consistency)**: 반영 지연(Staleness) 가능 |
| **시스템 결합도** | 두 서비스 간 런타임 의존성 존재 | 런타임 독립성 확보(장애 격리) |
| **운영 복잡도** | 단순(조회 실패/타임아웃 중심) | 이벤트 유실/중복/순서 보장 등 운영 복잡도 상승(Outbox/DLQ/Replay 필요) |

#### 💡 나의 판단 근거 (Decision Reason)
- **MSA의 본질 준수**: 상품 정보의 **Source of Truth(SoT)**는 Product 서비스여야 한다. Order가 데이터를 사전에 복제해 들고 있는 것은 도메인 경계를 흐리고 정합성 관리 비용을 높인다고 판단했다.
- **스냅샷의 신뢰도**: 스냅샷은 "결제 순간의 정확한 값"을 박제하는 기능이다. 0.1초의 갱신 지연으로 잘못된 값이 기록될 리스크를 배제하기 위해 **직접 조회(A안)**를 선택했다.
- **성능 최적화**: SKU 단건 반복 조회가 아닌 **List 기반의 Bulk 조회**를 적용하여 네트워크/DB I/O를 최소화했다.

---

### 2. 서비스 간 통신 모델: 동기(Blocking) vs 비동기(Non-blocking)

상품 조회를 수행할 때의 “처리 모델”에 대해 고민했다.

| 비교 항목 | A안: 동기식(Blocking) 처리 (선택) | B안: 비동기식(Non-blocking) 처리 (보류) |
| :--- | :--- | :--- |
| **응답성** | 즉각적인 성공/실패 판단 가능(Fail-fast) | 202 Accepted + 상태 조회 등 추가 상태 관리 필요 |
| **장애 전파** | Product 지연/장애가 주문 생성에 직접 영향 → 회복성 설계 필요 | 메시지 큐가 완충 역할(격리) |
| **구현 난이도** | 단순하고 직관적 | Correlation ID, timeout, 재처리, 분실/중복 응답 처리 등 복잡 |
| **자원 사용** | 대기 시간 동안 스레드/커넥션 점유 리스크 | 자원 효율 ↑, 처리량 확장 유리 |

#### 💡 나의 판단 근거 (Decision Reason)
- **정합성과 사용자 경험**: 주문 생성은 사용자가 즉시 결과를 받아야 하는 핵심 흐름이다. 동기(Blocking) 처리는 요청 누락을 방지하고 실패를 즉시 감지(Fail-fast)하기에 적합하다고 판단했다.
- **성능 리스크의 기술적 해결**: Blocking의 단점(커넥션 점유)은 **외부 통신(Service)과 DB 트랜잭션(TransactionalService) 경계를 분리**하는 구조로 완화했다.
- **구현 방식**: 실습 환경에서는 Product 조회를 **Kafka request-reply**로 구현하되, 주문 생성 흐름에서 응답을 기다리는 **동기(Blocking) 처리 모델**로 적용했다.

---

### 🔁 향후 확장 계획 (Future Scalability / 전환 트리거)

현재의 선택(A + Blocking)은 **"정합성 최우선"** 전략이다. 하지만 시스템 규모가 커짐에 따라 다음 트리거가 관측되면 확장을 진행할 예정이다.

1. **성능 병목**: Product 조회 latency가 주문 전체 p95/p99의 병목으로 확인될 때  
2. **가용성 요구**: Product 서비스의 일시적 순단이 주문 서비스 SLA에 치명적 영향을 줄 때  
3. **CQRS 전환 조건 충족**: Outbox/DLQ/Replay/Idempotency 등 운영 체계를 갖춰 **staleness를 통제**할 준비가 되었을 때  

---

## 8. 프로젝트 실행 방법

### 인프라 가동
```powershell
minikube start
minikube tunnel # LoadBalancer 서비스 노출을 위해 필수
