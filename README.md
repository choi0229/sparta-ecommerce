# sparta-msa-final-project (E-Commerce)

## 1. 프로젝트 개요

본 프로젝트는 **MSA 아키텍처** 환경에서 이커머스의 핵심인 **데이터 정합성과 일관성**을 보장하기 위한 분산 시스템 설계 및 고도화를 목표로 합니다. 특히 **Order-Inventory Saga** 구현 시 발생하는 병목 현상을 진단하고, 주문 처리 비동기 전환 및 상품 검색 Elasticsearch 도입을 통해 시스템 성능을 개선하는 실전적 해결 과정을 포함합니다.

또한 Claude Code를 활용한 AI Native 개발 흐름을 실험하기 위해, 단순 프롬프트 기반 개발이 아니라 **Rules, Skills, Guardrails, CI Gate**를 포함한 Claude Code 하네스를 구성하고, 이를 기반으로 신규 `logistics-api`를 추가했습니다.

- **핵심 목표**: 분산 트랜잭션 하에서의 TPS 최적화, 멱등성 보장, 데이터 스냅샷 유지, 검색 성능 고도화, AI Agent 기반 개발 흐름 검증.
- **해결 과제 1**: 외부 시스템 통신 시 DB 커넥션 점유 문제를 해결하여 주문 처리 가용성 극대화.
- **해결 과제 2**: LIKE 풀스캔 기반 검색의 한계를 단계적으로 개선하여 고부하 환경에서도 안정적인 검색 제공.
- **해결 과제 3**: 주문 이후 배송 도메인을 `logistics-api`로 분리하고, 이벤트 기반으로 배송 요청과 배송 상태를 관리.
- **해결 과제 4**: Claude Code가 프로젝트 규칙 안에서 안전하게 작업하도록 하네스와 CI Gate 구성.
- **해결 과제 5**: happy path 뿐 아니라 failure path까지 smoke script와 상태 조회 API로 검증 가능한 구조로 보강.
- **해결 과제 6**: Outbox 상태 관측성, stale recovery, admin retry, alert/runbook까지 포함한 운영 체계 정리.

---

## 2. 서비스 구성 (MSA)

- **product-api** (`:8081`): 상품 메타데이터 및 SKU(Variant) 관리, ES 검색 인덱스 동기화, 약식 결제 흐름 포함.
- **order-api** (`:8083`): 주문 생성, 주문 상태 관리, Saga 상태 관리, 주문 이력 보존, `shipment-event` 수신, product snapshot 실패 응답 처리.
- **inventory-api** (`:8082`): 재고 예약(Reservation), 결제 대기 TTL 만료, 재고 해제 및 보상 처리.
- **logistics-api** (`:8084`): 배송 요청 생성, 배송 상태 관리, 배송 상태 이력 저장, 주문 이벤트 수신, Transactional Outbox 기반 배송 이벤트 발행.
- **payment-api**: 독립 서비스가 아닌 `product-api` 내부에 약식 구현된 결제 흐름.
- **address-api** (`:8090`, 이미지 태그 `:real`): Spring Boot + PostgreSQL 기반 사용자 주소 관리 서비스. CRUD API 제공(`GET /addresses/{id}`, `GET /addresses?userId`, `POST`, `PATCH`, `DELETE` soft delete), 기본 배송지 1개 partial unique index, 삭제된 주소 조회 제외, 주소 변경/삭제 이력 저장(`user_address_history`) 및 이력 조회 API(`GET /addresses/{id}/histories?userId=&page=&size=&actionType=`). order-api의 `addressId` 기반 배송지 해소에 사용. `deployment/address-api/`로 배포.
- **mock-address-api** (`:8090`, 이미지 태그 `:mock`): `address-http` smoke 전용 WireMock 서버. 성공/404/503 시나리오 재현. `deployment/mock-address-api/`로 배포.

---

## 3. 기술 스택

- **Backend**: Spring Boot 3.x, Spring Data JPA, Hibernate, QueryDSL, Spring Data Elasticsearch
- **DB & Storage**: PostgreSQL (JSONB, GIN Index, LIST Partitioning), MinIO (S3 compatible)
- **Search**: Elasticsearch 7.17
- **Messaging**: Kafka (Choreography Saga, Transactional Outbox)
- **Infrastructure**: Kubernetes (Minikube), Docker, Docker Compose
- **Observability**: Prometheus, Grafana, Micrometer, ELK Stack
- **Testing**: JUnit5, Mockito, k6 Load Testing, smoke script
- **AI Native Development**: Claude Code, Claude Code Rules, Skills, Guardrails, GitHub Actions CI Gate

---

## 4. 핵심 고도화 내용

### 주문 처리 최적화 (동기 → 비동기 전환)

Kafka를 통한 상품 정보 획득 시 발생하던 **DB 커넥션 고갈 문제**를 단계적으로 해결했습니다.

**1단계: 트랜잭션 분리**

- `OrderService`(Kafka 이벤트 전송)와 `OrderTransactionalService`(`@Transactional` DB 작업)로 분리하여 Kafka 대기 구간 동안 DB 커넥션을 점유하지 않도록 개선
- 동기 방식 기준 10 rps까지 안정화 달성

**2단계: 비동기 전환 (202 Accepted + 폴링)**

- 트랜잭션 분리만으로는 고부하에서 스레드 블로킹 문제가 재현되어 구조 자체를 전환
- `POST /api/orders` → 즉시 202 Accepted 반환, Outbox → Kafka → Consumer → DB 저장 비동기 처리
- `GET /api/orders/status/{idemKey}` 폴링으로 처리 결과 확인
- in-memory `ConcurrentHashMap` 기반 `ProductSnapshotPendingStore` 제거 → 레플리카 간 응답 유실 문제 해결 및 수평 확장 가능

### 상품 검색 성능 최적화 (RDB → Elasticsearch 전환)

10만 건 데이터 기준 단계적 최적화를 통해 고부하 실패율 72% → 1.47%로 개선했습니다.

| 단계 | 고부하 실패율 | keyword p(95) |
|---|---:|---:|
| LIKE 기준선 | 72.33% | 6.00s |
| B-Tree 인덱스 추가 | 69.59% | 5.44s |
| FTS GIN 인덱스 | 0.00% | 41ms |
| 파티셔닝 + FTS | 29.57% | 1.87s |
| **ES 전환** | **1.47%** | **31ms** |

- FTS 고부하 실패율 0%로 성능 수치는 우수했으나, DB 커넥션 의존성·한국어 형태소 분석 한계·확장성을 고려해 ES를 최종 선택
- Kafka Outbox 패턴을 통해 상품 생성/수정/삭제 시 ES 인덱스 자동 동기화 구현
- ELK 스택의 ES를 로그 수집용으로 이미 운영 중이어서 추가 인프라 비용 없이 도입

### 멱등성 및 정합성 보장

- **Idempotency**: `Idempotency-Key` 기반으로 중복 주문 및 중복 결제 요청 방어
- **Snapshotting**: 상품명, 가격 등이 변동되어도 주문 시점의 데이터를 `jsonb` 형태로 영구 보관
- **Transactional Outbox**: DB 커밋과 이벤트 발행의 원자성 보장. 최대 5회 지수 백오프 재시도, FAILED 상태 전환으로 유실 방지
- **processed event / idempotency record**: Kafka 이벤트 중복 전달 상황에서도 중복 주문, 중복 재고 처리, 중복 배송 생성이 발생하지 않도록 방어

### logistics-api: 주문 이후 배송 도메인 분리 및 이벤트 연동

주문 이후의 배송 요청 생성과 배송 상태 관리를 담당하는 `logistics-api`를 신규 서비스로 추가하고, `order-api`와의 이벤트 연동을 안정화했습니다.

**도메인 경계**

`logistics-api`는 배송/물류 도메인의 상태만 소유하며, `order-api` DB에 직접 접근하지 않습니다. 배송 상태 변경 결과는 Transactional Outbox 기반으로 `shipment-event`를 발행하고, `order-api`가 이를 수신해 `orders.shipment_status`를 갱신합니다.

**배송 상태 전이**

```text
READY      → SHIPPED, CANCELED
SHIPPED    → IN_TRANSIT, FAILED, CANCELED
IN_TRANSIT → DELIVERED, FAILED
DELIVERED  → 변경 불가
FAILED     → 변경 불가
CANCELED   → 변경 불가
```

**Outbox Publisher 개선 — Native Claim 방식**

기존 JPA `PESSIMISTIC_WRITE` 기반 배치 조회는 `@Transactional(REQUIRES_NEW)` 분리 후에도 `idle in transaction` 상태에서 잠금이 해제되지 않아 후속 발행이 차단되는 문제가 있었습니다.

이를 PostgreSQL Native SQL `WITH cte AS (SELECT ... FOR UPDATE SKIP LOCKED) UPDATE ... RETURNING id` 방식으로 전환했습니다.

- PENDING 행 선택과 PROCESSING 상태 전이를 단일 DML로 원자적으로 처리
- `next_retry_at`에 claim 만료 시각(`now + 2분`)을 기록해 별도 컬럼 추가 없이 stale 판단 기준으로 재사용
- `FOR UPDATE SKIP LOCKED`로 다중 인스턴스 환경에서도 충돌 없이 배치 처리 가능

**Stale PROCESSING Recovery**

Pod 비정상 종료 등으로 PROCESSING 상태가 만료 기한을 넘겨도 SENT로 전환되지 않는 경우, 60초 주기 스케줄러가 해당 행을 PENDING으로 되돌리고 `retry_count`를 증가시킵니다.

추가로 recovery 정책을 정교화했습니다.

- 한 cycle에 **최대 100건**까지만 복구하도록 batch 상한 추가
- `retry_count >= 3`인 stale row를 별도 high-retry 신호로 집계
- `logistics_outbox_stale_high_retry_total` 메트릭 추가
- terminal `FAILED` 전이 시 `[OutboxTerminal]` WARN 로그 추가

**Order-api 상태 조회 정합성 개선**

기존 `GET /api/orders/status/{idemKey}`는 `idempotency_request.status`를 반환해 `orders.status`(`CREATED`)와 불일치하는 경우가 있었습니다. 수정 후 `orders` 행이 존재하면 `orders.status`를 우선 반환하고, 행이 없을 때만 `idempotency_request.status`로 fallback합니다.

### Failure path 명시화: INVALID SKU → FAILED + failureReason

happy path가 안정화된 이후, 존재하지 않는 SKU 요청이 **영원히 PENDING에 머무르지 않고 명시적으로 FAILED로 종료**되도록 `order-api`와 `product-api`를 보강했습니다.

- `order-api`는 product snapshot 실패 응답(`success=false` 또는 `error!=null`)을 `FAILED`로 처리
- `IdempotencyRecord`에 `failureReason` 저장
- `GET /api/orders/status/{idemKey}` 응답에 `failureReason` 포함
- `product-api`는 실패 reply에도 `idemKey`, `userId`, `requestItem` 등 correlation field 유지

예시 응답:

```json
{
  "data": {
    "idemKey": "...",
    "status": "FAILED",
    "orderId": null,
    "shipmentStatus": null,
    "failureReason": "MISSING_SKU=[SKU-INVALID]"
  }
}
```

### FAILED outbox_event 운영 채널 보강

`logistics-api`의 admin outbox 재처리 채널도 운영 친화적으로 보강했습니다.

- `GET /admin/outbox?status=FAILED`로 FAILED 이벤트 조회
- `POST /admin/outbox/{id}/retry` 단건 재처리
- `POST /admin/outbox/retry?limit=20` 배치 재처리
- 단건/배치 재처리 응답에 `eventType`, `aggregateId`, `items` 등 운영 해석 정보 포함
- `logistics_outbox_admin_retry_total{type=single|batch}` 메트릭 추가

또한 DLQ는 지금 즉시 도입하지 않고, **수동 재처리 + 모니터링 + alert/runbook** 체계로 운영하는 방향을 ADR로 정리했습니다.

### 주문 시점 배송지 snapshot 도입

배송지 정보도 상품 snapshot과 동일하게 **주문 시점 사실**로 저장하도록 보강했습니다.

- `CreateOrderRequest.shippingAddress` 추가
- `orders.recipient_name`, `orders.recipient_address` 컬럼 추가
- `OrderCreatedEvent`에 `recipientName`, `recipientAddress` 포함
- `logistics-api`는 해당 값을 받아 `shipment.recipient_name`, `shipment.recipient_address`에 저장

이를 통해 주문 생성 시점의 배송지 정보가 `order-api`와 `logistics-api` 양쪽에 일관되게 반영되며, 이후 사용자가 배송지 변경을 요청하더라도 **주문 당시 snapshot은 보존**됩니다.

### 배송지 수정 API 추가 (`READY` 상태 한정)

초기 배송지 snapshot 저장 이후, 실제 배송 처리 대상 주소는 `logistics-api`의 `shipment`만 수정할 수 있도록 API를 추가했습니다.

- `PATCH /shipments/{shipmentId}/address`
- request body:
  ```json
  {
    "recipientName": "김철수",
    "recipientAddress": "부산시 해운대구 달맞이길 1"
  }
  ```
- `READY` 상태일 때만 수정 허용
- `SHIPPED`, `IN_TRANSIT`, `DELIVERED`, `FAILED`, `CANCELED` 상태에서는 수정 불가
- 수정 시 `orders.recipient_name`, `orders.recipient_address`는 변경하지 않음

즉, `order-api`의 배송지 snapshot은 **주문 당시 사실 기록**, `logistics-api`의 `shipment` 주소는 **현재 배송 처리 대상 주소**로 역할을 분리했습니다.

### 배송지 변경 이력 저장 (append-only)

배송지 수정 API 도입 이후, 운영/CS 관점에서 변경 이력을 추적할 수 있도록 `shipment_address_history` 테이블을 추가했습니다.

- `shipment.recipient_name`, `shipment.recipient_address`는 **현재 배송 처리 대상 주소**
- `shipment_address_history`는 **주소 변경 이력 append-only 저장소**
- 이력에는 변경 전/후 주소와 변경 시각(`changed_at`)을 저장
- 동일한 값으로 다시 요청한 경우에는 이력을 중복 저장하지 않음

저장 예시 컬럼:
- `shipment_id`
- `previous_recipient_name`
- `previous_recipient_address`
- `new_recipient_name`
- `new_recipient_address`
- `changed_at`

이를 통해 주문 당시 snapshot(`orders.recipient_*`)은 그대로 유지하면서, 실제 배송 주소 변경 내역은 별도로 추적할 수 있게 했습니다.


### addressId 기반 주문 배송지 해소 (MVP 1단계)

주문 생성 시 배송지 정보를 직접 입력하는 방식에 더해, 저장된 사용자 주소를 참조할 수 있도록 `addressId` 기반 배송지 해소 로직을 추가했습니다.

- `CreateOrderRequest`에 `addressId` 필드 추가
- `OrderService.createOrder()`에서 배송지를 먼저 해소한 뒤 기존 주문 이벤트 흐름에 주입
- `addressId`가 있으면 `AddressServiceClient`를 통해 조회한 주소를 사용
- `addressId`가 없고 `shippingAddress`가 있으면 직접 입력 값을 사용
- `addressId`와 `shippingAddress`가 동시에 오면 `addressId`를 우선 사용
- 둘 다 없으면 `SHIPPING_ADDRESS_REQUIRED`로 주문 차단
- 존재하지 않는 `addressId`는 `ADDRESS_NOT_FOUND`로 주문 차단
- **주소 소유자 검증**: `HttpAddressServiceClient`가 `GET /addresses/{id}?userId={userId}` 형태로 호출해, address-api에서 `order.userId == address.userId` 불일치 시 404를 반환하면 order-api는 `ADDRESS_NOT_FOUND`로 차단. 보안상 소유자 불일치와 미존재를 동일하게 처리.

초기에는 `StubAddressServiceClient`를 사용해 addressId 기반 흐름을 먼저 검증했습니다. 이후 real `address-api`(Spring Boot + PostgreSQL)를 구축하고 `HttpAddressServiceClient`로 전환해 실제 서비스 연동까지 완성했습니다. 기존 `shippingAddress` 직접 입력 경로는 그대로 유지됩니다.


### AddressServiceClient 설정 기반 분리 (stub ↔ http 전환 준비)

실제 주소 서비스 연동으로 자연스럽게 확장할 수 있도록 `AddressServiceClient`를 설정 기반으로 분리했습니다.

- `address.client.mode=stub\|http` 설정 추가
- 기본값은 `stub`로 두어 기존 addressId 기반 E2E 검증이 깨지지 않도록 유지
- `HttpAddressServiceClient`를 추가해 향후 실제 주소 서비스의 `GET /addresses/{id}` 호출 구조를 준비
- `AddressClientConfig`에서 mode에 따라 `StubAddressServiceClient` 또는 `HttpAddressServiceClient`를 bean으로 선택
- `AddressClientProperties`로 `base-url`, `connect-timeout-ms`, `read-timeout-ms`를 설정 바인딩
- HTTP 구현체는 `404 → ADDRESS_NOT_FOUND`, `5xx/타임아웃/기타 오류 → ADDRESS_LOOKUP_FAILED`로 예외를 매핑

초기에는 stub 기반으로 개발·검증하고, 이후 real `address-api` 구축 후 설정 변경만으로 HTTP 구현체로 전환해 실제 서비스 연동을 완성했습니다.


### address-api / mock-address-api 분리 구조

real 검증과 smoke 검증을 이름·태그·배포 경로 기준으로 완전히 분리합니다.

| 구분 | 이미지 태그 | 배포 경로 | Service 이름 | 용도 |
|---|---|---|---|---|
| real | `:real` | `deployment/address-api/` | `address-api-svc` | 실서비스 연동 검증 |
| mock | `:mock` | `deployment/mock-address-api/` | `mock-address-api-svc` | smoke 전용 (404/503 포함) |

#### order-api ADDRESS_CLIENT_BASE_URL 설정 예시

```bash
# mock 검증용 (address-http smoke / WireMock)
kubectl set env deployment/order-api -n ecommerce \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://mock-address-api-svc:8090

# real 검증용 (Spring Boot address-api)
kubectl set env deployment/order-api -n ecommerce \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090
```

#### real address-api 배포 runbook

```bash
# 1. address-db 기동 (최초 1회)
kubectl apply -f deployment/infra/db/address-db.yaml
kubectl rollout status statefulset/address-db -n ecommerce --timeout=120s

# 2. 이미지 빌드 및 minikube 로드
docker build -t sparta-msa-final-project-address-api:real ./address-api
minikube image load sparta-msa-final-project-address-api:real

# 3. Deployment 적용
kubectl apply -f deployment/address-api/
kubectl rollout status deployment/address-api -n ecommerce --timeout=120s

# 4. order-api를 real 모드로 전환
kubectl set env deployment/order-api -n ecommerce \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090
kubectl rollout status deployment/order-api -n ecommerce --timeout=120s
```

검증 완료 항목:
- `GET /addresses/1` → `홍길동 / 서울시 강남구 테헤란로 1`
- `GET /addresses/999` → 404
- `order-api` http 모드에서 `addressId=1` 주문 생성 → `status=CREATED`, `shipmentStatus=READY`
- CRUD API 및 soft delete → `e2e-order-address-real-smoke.sh` 전 과정 검증 완료

### real address-api 사용자 주소 관리 서비스 확장

MVP(`GET /addresses/{id}`) 이후 사용자 주소 관리 서비스로 확장했습니다.

- **CRUD API**: `GET /addresses?userId` (전체 목록 조회, 유지), `POST /addresses`, `PATCH /addresses/{id}`, `DELETE /addresses/{id}` (soft delete)
- **주소 목록 페이징 조회**: `GET /addresses/page?userId={userId}&page=0&size=20` — deleted=false만 포함, 기본 배송지 우선(`isDefault DESC, id DESC`), size 최대 100, page/size 범위 위반 시 400. 응답: `AddressPageResponse`(content, page, size, totalElements, totalPages, hasNext)
- **Bean Validation**: POST — `userId` NotNull, `recipientName`/`recipientAddress` NotBlank. PATCH — null은 미수정 허용, 비어 있는 문자열은 400 차단
- **soft delete**: `deleted=true` 처리 후 조회 제외. 삭제된 `addressId`로 order-api 주문 생성 시 `ADDRESS_NOT_FOUND`(404) 차단
- **기본 배송지 1개 정책**: 사용자별 `is_default=true` 행을 최대 1개로 제한하는 partial unique index (V3 Flyway). 새 기본 배송지 지정 시 기존 기본 배송지 자동 해제
- **real smoke 자동화**: `e2e-order-address-real-smoke.sh` — 7단계 자동화: 주소 생성(userId=9001) → 정상 주문 polling → 다른 userId(9002)로 같은 addressId 주문 시 `ADDRESS_NOT_FOUND` 차단 → soft delete → 삭제된 addressId로 주문 `ADDRESS_NOT_FOUND` 차단

### 사용자 주소 변경/삭제 이력 저장

주소 CRUD 작업마다 변경 전/후 값을 `user_address_history` 테이블에 append-only로 저장합니다.

- **CREATE**: `after_*` = 생성 값, `before_*` = null, `action_type=CREATE`
- **UPDATE**: 실제 변경이 있을 때만 저장 — `before_*` = 변경 전 값, `after_*` = 변경 후 값, `action_type=UPDATE`. 동일한 값으로 재요청하면 이력 미저장
- **DELETE**: `before_*` = soft delete 전 값, `after_*` = null, `action_type=DELETE`
- **기본 배송지 자동 해제 이력**: 새 기본 배송지 지정 시 기존 기본 배송지의 `isDefault=true→false` 변경도 `actionType=UPDATE` 이력으로 저장. CREATE/UPDATE 모두 동일하게 적용. isDefault=true 요청 시 이미 그 주소가 기본 배송지인 경우에는 자동 해제 이력 미저장
- **트랜잭션 경계**: 이력 저장은 주소 변경과 동일한 `@Transactional` 내에서 수행 — 이력 저장 실패 시 주소 변경도 함께 롤백
- **V4 Flyway**: `V4__add_user_address_history.sql`로 테이블 추가

### 사용자 기본 배송지 조회 API

`GET /addresses/default?userId={userId}`

- **userId 필수**: 미전달 시 400. `userId`에 해당하는 active(deleted=false) 기본 배송지 반환
- **기본 배송지 없음**: 404 반환. soft delete된 주소는 결과에서 제외
- **라우팅 우선순위**: Spring MVC literal path(`/default`)가 template(`/{id}`)보다 우선 처리되므로 `/addresses/{id}`와 충돌 없음
- **응답**: `AddressDetailResponse` — id, userId, recipientName, recipientAddress, isDefault

### 주소 변경 이력 조회 API (페이징/필터)

`GET /addresses/{id}/histories?userId={userId}&page=0&size=20&actionType=UPDATE`

- **소유자 검증**: `userId`와 `address.userId` 불일치 시 404. 존재하지 않는 주소도 동일하게 404로 처리해 소유 여부를 외부에 노출하지 않음
- **삭제된 주소 조회 가능**: soft delete된 주소라도 본인 소유라면 이력 조회 허용. 기존 `GET /addresses/{id}?userId=`는 `deleted=false` 조건 그대로 유지
- **페이징**: `page` (기본값 0), `size` (기본값 20, 최대 100). page < 0 / size ≤ 0 / size > 100 → 400
- **actionType 필터**: `CREATE` / `UPDATE` / `DELETE` 중 하나. 생략 시 전체 조회. 잘못된 값 → 400
- **정렬**: changedAt DESC (엔티티 필드명 `createdAt` 기준. changedAt은 history row가 생성된 시각)
- **응답**: `AddressHistoryPageResponse` — content(List), page, size, totalElements, totalPages, hasNext

### 관리자용 전체 이력 조회/검색 API

`GET /admin/addresses/histories?userId=&addressId=&actionType=&from=&to=&page=0&size=20`

- **접근 통제**: `X-Admin-Api-Key` 헤더 필수. 환경변수 `ADDRESS_ADMIN_API_KEY`로 설정. 헤더 누락/불일치/설정값 미지정 시 403
- **운영 전환 가이드**: 현재 정식 인증/권한 시스템은 없으므로 `X-Admin-Api-Key` 기반 임시 접근 통제를 사용한다. 운영 환경에서는 게이트웨이/내부망 접근 통제 또는 Spring Security 기반 관리자 권한 검증으로 대체해야 한다.
- **모든 파라미터 optional**: 생략 시 전체 이력 조회. userId/addressId/actionType/날짜 범위 조합 가능
- **날짜 범위**: `from`/`to`는 ISO-8601 형식 (`2024-01-01T00:00:00`). from > to이면 400
- **actionType 필터**: `CREATE` / `UPDATE` / `DELETE`. 잘못된 값 → 400
- **페이징**: `page` (기본값 0), `size` (기본값 20, 최대 100). 범위 위반 → 400
- **정렬**: createdAt DESC (고정)
- **응답**: `AddressHistoryPageResponse` — content(List), page, size, totalElements, totalPages, hasNext

### 이력 보존 정책 드라이런 조회 API

`GET /admin/addresses/histories/retention-dry-run?retentionMonths=12`

- **접근 통제**: `X-Admin-Api-Key` 헤더 필수. 헤더 누락/불일치 시 403
- **retentionMonths 필수**: 1 이상 120 이하 정수. 범위 위반 시 400
- **동작**: `cutoffAt = now - retentionMonths개월` 기준으로 이전 이력 건수를 조회한다. **실제 삭제/아카이빙은 수행하지 않는다.**
- **ADR-002 D안 준수**: 현재 무기한 보존 정책 유지 중 — 이 API는 정책 영향도를 사전 파악하기 위한 read-only 기능이다.
- **응답**: `AddressHistoryRetentionDryRunResponse` — retentionMonths, cutoffAt, candidateCount, action(`DRY_RUN_ONLY`), message

#### mock address-api (smoke 전용)

`address-http` smoke는 항상 `:mock` 태그 + `deployment/mock-address-api/`만 사용합니다. WireMock은 503 시나리오까지 재현 가능한 smoke 자산이며, 실서비스 대체가 아닙니다.

- `address-api/Dockerfile.mock` + `address-api/mappings/addresses.json` 사용
- `GET /addresses/999` → 404, `GET /addresses/503` → 503 재현
- smoke script (`e2e-order-address-http-smoke.sh`) 에서 자동 빌드·배포·검증 후 order-api env 복원
- mapping은 `urlPath` 기준 매칭 — order-api가 `GET /addresses/{id}?userId={userId}` 형태로 호출하더라도 query string을 무시하고 path만으로 매칭함 (owner 검증은 real smoke에서 담당)
- `:mock` 태그를 재사용하므로 Deployment spec 변경 없이 image load만으로는 Pod가 교체되지 않음 — smoke script에서 `kubectl rollout restart`로 강제 재시작해 새 mapping을 반드시 반영함

---

## 5. 이벤트 흐름

주문 생성부터 배송 상태가 order-api에 반영되기까지의 전체 흐름입니다.

```text
[Client]
  POST /api/orders
    │
    ▼
[order-api]
  202 Accepted
  orders 저장 (status=CREATED)
  outbox_event 저장 (order-create-event)
    │ Kafka
    ▼
[logistics-api — OrderEventConsumer]
  shipment 생성 (status=READY)
  shipment_status_history 저장
  outbox_event 저장 (shipment-created-event)
    │ Kafka
    ▼
[order-api — ShipmentEventConsumer]
  orders.shipment_status = READY 반영
    │
    ▼ (배송사 처리 or PATCH /shipments/{id}/status 호출)
[logistics-api]
  shipment 상태 변경 (→ SHIPPED / IN_TRANSIT / DELIVERED …)
  shipment_status_history 저장
  outbox_event 저장 (shipment-status-changed-event)
    │ Kafka
    ▼
[order-api — ShipmentEventConsumer]
  orders.shipment_status 반영
    │
    ▼
[Client]
  GET /api/orders/status/{idemKey}
  → { status: "CREATED", shipmentStatus: "IN_TRANSIT" }
```

**배송지 snapshot 포함 주문 흐름**

```text
[Client]
  POST /api/orders
  shippingAddress { recipientName, recipientAddress }
    │
    ▼
[order-api]
  orders.recipient_name / recipient_address 저장
  OrderCreatedEvent(recipientName, recipientAddress) 발행
    │ Kafka
    ▼
[logistics-api]
  shipment.recipient_name / recipient_address 저장
```

**배송지 수정 흐름**

```text
[Client]
  PATCH /shipments/{shipmentId}/address
    │
    ▼
[logistics-api]
  READY 상태 확인
  shipment.recipient_name / recipient_address 수정
    │
    ▼
[order-api]
  orders.recipient_name / recipient_address 는 그대로 유지
```

**실패 흐름 (존재하지 않는 SKU)**

```text
[Client]
  POST /api/orders (SKU-INVALID)
    │
    ▼
[order-api]
  202 Accepted 반환
  productSnapshot-requested-event 발행
    │ Kafka
    ▼
[product-api]
  MISSING_SKU 감지
  productSnapshot-reply-event(success=false, error=MISSING_SKU, idemKey 유지) 발행
    │ Kafka
    ▼
[order-api — OrderEventConsumer]
  IdempotencyRecord FAILED 처리
  failureReason 저장
    │
    ▼
[Client]
  GET /api/orders/status/{idemKey}
  → { status: "FAILED", orderId: null, shipmentStatus: null, failureReason: "MISSING_SKU=[SKU-INVALID]" }
```

**outbox_event 상태 전이**

```text
PENDING → PROCESSING (claim)
        → SENT       (발행 성공)
        → FAILED     (최대 재시도 초과)
PROCESSING → PENDING (stale recovery: 만료 후 60s 주기 복구)
FAILED → PENDING     (admin retry: 단건/배치 수동 재처리)
```

---

## 6. 운영 관측성

세 서비스 모두 `/actuator/prometheus` 엔드포인트를 노출합니다 (`management.endpoints.web.exposure.include: prometheus,health,info,metrics`). logistics-api를 중심으로 시작했던 메트릭을 `order-api`, `inventory-api`까지 확장해 **Outbox 상태를 서비스별로 같은 패턴으로 비교**할 수 있도록 보강했습니다.

### logistics-api 메트릭 (포트 8084)

| 메트릭 | 설명 |
|---|---|
| `logistics_outbox_events{status}` | outbox_event 상태별 현재 건수 (`PENDING / PROCESSING / SENT / FAILED`) |
| `logistics_outbox_publish_total{result=sent\|failed}` | Kafka 발행 성공/실패 누적 수 |
| `logistics_outbox_stale_recovered_total` | stale PROCESSING → PENDING 복구 누적 수 |
| `logistics_outbox_stale_high_retry_total` | `retry_count >= 3` stale row 복구 감지 누적 수 |
| `logistics_outbox_admin_retry_total{type=single\|batch}` | 운영자 단건/배치 재처리 누적 수 |
| `logistics_order_event_consume_total{result=success\|failed}` | `order-create-event` 소비 성공/실패 누적 수 |

### order-api 메트릭 (포트 8083)

| 메트릭 | 설명 |
|---|---|
| `order_outbox_events{status}` | outbox_event 상태별 현재 건수 (`PENDING / SENT / FAILED`) |
| `order_outbox_publish_total{result=sent\|failed}` | outbox 발행 성공/실패 누적 수 |
| `order_shipment_event_consume_total{result=success\|failed}` | `shipment-event` 소비 성공/실패 누적 수 |
| `order_shipment_status_update_total{result=success\|failed}` | `orders.shipment_status` 갱신 성공/실패 누적 수 |

### inventory-api 메트릭 (포트 8082)

| 메트릭 | 설명 |
|---|---|
| `inventory_outbox_events{status}` | outbox_event 상태별 현재 건수 (`PENDING / SENT / FAILED`) |
| `inventory_outbox_publish_total{result=sent\|failed}` | outbox 발행 성공/실패 누적 수 |

### Prometheus Alert / Runbook

logistics-api Outbox 운영을 위해 Prometheus alert rule과 runbook 초안을 추가했습니다. 현재는 **DLQ를 즉시 도입하지 않고**, FAILED gauge / publish failed / stale high-retry / stale recovery 빈도 기반으로 운영 신호를 감지하는 방향을 채택했습니다.

- Alert rule: `prometheus/alerts/logistics-outbox-alerts.yml`
- Runbook: `docs/runbooks/logistics-outbox-alerts.md`
- ADR: `docs/adr/001-outbox-dlq-decision.md`

대표 알림 예시:
- `LogisticsOutboxFailedEventsPresent` — FAILED row가 5분 이상 존재
- `LogisticsOutboxFailedEventsSurge` — FAILED row 급증
- `LogisticsOutboxPublishErrorsDetected` — 최근 5분간 publish failed 증가
- `LogisticsOutboxHighRetryEventsDetected` — high-retry stale row 감지
- `LogisticsOutboxStaleRecoveryFrequent` — stale recovery 빈발 감지

### 메트릭 조회

```bash
# logistics-api
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce
curl -s http://localhost:8084/actuator/prometheus | grep "logistics_outbox"

# order-api
kubectl port-forward svc/order-api-svc 8083:8083 -n ecommerce
curl -s http://localhost:8083/actuator/prometheus | grep "order_outbox"

# inventory-api
kubectl port-forward svc/inventory-api-svc 8082:8082 -n ecommerce
curl -s http://localhost:8082/actuator/prometheus | grep "inventory_outbox"
```

**admin retry / stale recovery 메트릭 확인**

```bash
curl -s http://localhost:8084/actuator/prometheus | grep "admin_retry"
curl -s http://localhost:8084/actuator/prometheus | grep "outbox_stale"
```

---

## 7. 개발 기능 정의 (MVP Status)

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
| | `shipment-event` 수신 | order-api가 `shipment-event`를 수신해 `orders.shipment_status` 반영 |
| | 배송 상태 조회 | `GET /api/orders/status/{idemKey}` 에서 `orders.status` 우선 반영 |
| | 상품 스냅샷 실패 처리 | invalid SKU 등 실패 응답을 `FAILED + failureReason`으로 종료 |
| | FAILED outbox 운영 복구 | admin retry API + 수동 재처리 메트릭 지원 |
| | 주문 시점 배송지 snapshot 저장 | 주문 생성 시 `shippingAddress`를 `orders`와 `shipment`에 반영하고 주문 당시 사실로 보존 |
| | 배송지 수정 API | `PATCH /shipments/{shipmentId}/address`로 `READY` 상태 배송의 현재 주소만 수정 |
| | 배송지 변경 이력 저장 | `shipment_address_history`에 변경 전/후 주소와 변경 시각을 append-only로 저장 |
| | addressId 기반 주문 배송지 해소 | `addressId`가 있으면 저장된 주소를 조회해 snapshot에 반영하고, 없으면 `shippingAddress` 직접 입력을 사용 |
| | AddressServiceClient 설정 분리 | `address.client.mode=stub\|http` 설정으로 stub/http 구현체를 전환 가능하게 준비 |
| **Could-Have** | 데이터 유실 방지 | DB 저장/이벤트 발행 불일치 방지를 위해 **Transactional Outbox** |
| | 운영 관측성 | Micrometer 기반 메트릭(Gauge/Counter) → `/actuator/prometheus` 노출 |
| | smoke script 기반 운영 검증 | happy path / negative path를 bash script로 재현 가능 |
| | alert / runbook 기반 운영 대응 | FAILED, publish failed, stale recovery 기준 운영 대응 |

---

## 8. 성능 테스트 결과

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

## 9. 아키텍처 결정 이력 (Architecture Decision Record)

### 1. 상품 정보 확보 전략: 직접 조회(A) vs Read Model(B)

| 비교 항목 | A안: Product 서비스 직접 조회 (선택) | B안: Order 내 Read Model 유지 (보류) |
|:---|:---|:---|
| **동작 방식** | 주문 시점에 Product에서 최신 정보 조회 후 Snapshot 저장 | Product 변경 이벤트를 구독하여 Order DB에 복제본 유지 |
| **데이터 정합성** | 강한 일관성(Strong Consistency) | 결과적 일관성(Eventual Consistency), 반영 지연 가능 |
| **시스템 결합도** | 두 서비스 간 런타임 의존성 존재 | 런타임 독립성 확보 |
| **운영 복잡도** | 단순(조회 실패/타임아웃 중심) | Outbox/DLQ/Replay 등 운영 복잡도 상승 |

**판단 근거**: 스냅샷은 "결제 순간의 정확한 값"을 박제하는 기능이므로 SoT인 Product 서비스에서 직접 조회하는 A안 선택. SKU 단건 반복 조회가 아닌 List 기반 Bulk 조회로 I/O 최소화.

### 2. 주문 처리 모델: 동기(Blocking) → 비동기(Non-blocking) 전환

| 비교 항목 | 동기식 (초기 구현) | 비동기식 (최종 선택) |
|:---|:---|:---|
| **응답성** | 즉각적인 성공/실패 판단 | 202 Accepted + 폴링 |
| **자원 사용** | Kafka 대기 중 스레드/커넥션 점유 | 자원 즉시 반환 |
| **수평 확장** | in-memory 상태로 레플리카 확장 불가 | Outbox 기반으로 레플리카 자유롭게 확장 |
| **구현 난이도** | 단순 | Correlation ID, 폴링 API 추가 필요 |

**판단 근거**: 트랜잭션 분리로 10 rps까지는 안정화했으나 그 이상에서 스레드 블로킹 문제 재현. Redis 기반 공유 상태 저장도 검토했으나 블로킹 구조 자체는 해결 불가. 비동기 전환으로 커넥션 고갈과 레플리카 문제를 동시에 해결.

### 3. 검색 엔진: FTS(PostgreSQL) vs Elasticsearch

| 비교 항목 | FTS GIN | Elasticsearch (선택) |
|:---|:---|:---|
| **고부하 실패율** | 0.00% | 1.47% |
| **DB 커넥션** | 사용 (검색 부하가 DB 전체에 영향) | 미사용 (DB와 완전 분리) |
| **한국어 형태소** | simple 딕셔너리 (미지원) | nori 플러그인 지원 |
| **고급 검색** | 유사어/오타 교정 별도 구현 필요 | 기본 제공 |
| **인프라 비용** | 추가 없음 | ELK 스택 재활용 (추가 없음) |

**판단 근거**: 성능 수치만 보면 FTS가 더 우수하나, DB 부하 분리·한국어 검색 품질·향후 확장성을 종합해 ES 선택. 이미 운영 중인 ELK 스택 재활용으로 추가 인프라 비용 없음.

### 4. logistics-api 통합 전략: order-api 변경(B) vs logistics-api 적응(A)

| 비교 항목 | A안: logistics-api가 기존 order 이벤트에 맞춤 (선택) | B안: order-api 이벤트 구조 확장 |
|:---|:---|:---|
| **동작 방식** | 기존 `order-create-event`를 logistics-api가 수신 | 주문 요청/엔티티/이벤트에 배송지 정보 추가 |
| **기존 서비스 영향** | order-api 수정 없음 | order-api DTO, Entity, Event, Migration 수정 필요 |
| **구현 범위** | logistics-api 내부 수정 중심 | order-api와 logistics-api 동시 수정 |
| **배송지 정보** | MVP에서는 null 허용, 추후 보강 | 주문 생성 시점부터 배송지 정보 포함 |

**판단 근거**: 초기 목표는 `logistics-api`를 독립 서비스로 추가하고 기존 주문 흐름을 깨지 않는 것이었습니다. 따라서 1차 MVP에서는 `logistics-api`가 현재 `order-api`의 `order-create-event` 구조에 맞추는 A안을 선택했습니다.

### 5. Outbox Publisher 잠금 방식: JPA PESSIMISTIC_WRITE vs Native Claim

| 비교 항목 | JPA PESSIMISTIC_WRITE | Native Claim (선택) |
|:---|:---|:---|
| **잠금 방식** | `SELECT ... FOR UPDATE` (Spring 관리) | `UPDATE ... RETURNING` (단일 DML) |
| **잠금 해제 시점** | 외부 트랜잭션 커밋 시 해제 | SELECT 잠금 없음, DML 완료 즉시 |
| **다중 인스턴스** | `SKIP LOCKED` hint 지원 불안정 | `FOR UPDATE SKIP LOCKED` 명시적 보장 |
| **구현 복잡도** | `@Transactional(REQUIRES_NEW)` 분리 필요 | EntityManager Native Query 1개 |

**판단 근거**: `@Transactional(REQUIRES_NEW)` 분리 후에도 `pg_stat_activity`에서 `idle in transaction` 상태가 지속되며 잠금이 해제되지 않는 문제가 재현됨. QueryDSL hint `-2` (`SKIP_LOCKED`)도 런타임에서 `FOR UPDATE SKIP LOCKED`를 생성하지 않았음. Native SQL 방식으로 전환 후 해결.

### 6. AI Native 개발 방식: 단순 프롬프트 사용 vs Claude Code 하네스 구성

| 비교 항목 | 단순 프롬프트 기반 개발 | Claude Code 하네스 기반 개발 (선택) |
|:---|:---|:---|
| **작업 방식** | 매번 자연어로 요청 | `CLAUDE.md`, rules, skills, guardrails 기반 작업 |
| **규칙 일관성** | 세션마다 흔들릴 수 있음 | 서비스별 rules와 공통 rules로 일관성 유지 |
| **작업 범위 통제** | 명시하지 않으면 기존 코드까지 수정 가능 | path-scoped rules와 금지 조건으로 범위 제한 |
| **반복 작업** | 매번 절차 설명 필요 | Skill로 배포/신규 API 생성 절차 재사용 |
| **검증 방식** | 수동 테스트 중심 | Guardrails + GitHub Actions CI Gate로 자동 검증 |

### 7. logistics-api Outbox DLQ 도입 여부

현재는 **DLQ 미도입**을 선택했습니다.

- 이유
  - FAILED 상태가 이미 DB-level 격리 역할을 수행
  - `GET /admin/outbox?status=FAILED`로 즉시 조회 가능
  - admin retry API로 수동 재처리 가능
  - stale/high-retry/FAILED gauge/alert로 운영 신호 확보
  - 현재 이벤트 종류가 제한적이어서 broken row가 대량 발생할 구조가 아님

- 재검토 기준
  - FAILED row 일평균 100건 이상
  - 자동 알림 연동이 필수인 시점
  - 영구 실패와 재시도 가능 실패를 코드 레벨에서 구분해야 하는 시점

관련 문서:
- `docs/adr/001-outbox-dlq-decision.md`

---

## 10. Claude Code 하네스 및 CI Gate

본 프로젝트는 Claude Code를 활용한 AI Native 개발 흐름을 실험하기 위해 Claude Code 하네스를 구성했습니다.

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

### CI / Workflow

```text
.github/workflows/claude-ci-gate.yml
.github/workflows/integration-tests.yml
.github/workflows/smoke-tests.yml
scripts/claude-guardrails.sh
```

- **CI Gate**: `guardrails` + 서비스별 unit test matrix(`product-api`, `order-api`, `inventory-api`, `logistics-api`) + `logistics-api build`
- **integration-tests.yml**: `workflow_dispatch` 기반 통합 테스트 전용 워크플로
- **smoke-tests.yml**: `workflow_dispatch` 기반 smoke 실행 워크플로 (`happy / negative / address-http / all` 선택)
  - 현재 smoke 스크립트가 `localhost:8083`, `localhost:8084`를 사용하므로 **GitHub-hosted runner에서는 바로 실행 불가**
  - self-hosted runner 또는 runner에서 접근 가능한 환경이 필요
  - `address-http` 시나리오는 runner에 `docker`, `minikube` 추가 필요 (`happy` / `negative` 는 kubectl만 필요)
  - 각 시나리오 실행 로그는 GitHub Actions artifact로 자동 업로드됨 (성공/실패 무관)
  - happy path polling: `MAX_WAIT_SECONDS=60` (Kafka/Outbox 비동기 처리로 minikube 환경에서 30초를 초과할 수 있음)
  - port-forward 충돌 시 기존 kubectl port-forward 프로세스만 자동 정리 후 재시도; 다른 프로세스가 점유 시 즉시 실패

### 운영/검증 자동화 보강

- `scripts/smoke/e2e-order-shipment-smoke.sh`
  - happy path smoke
  - 주문 생성 → `shipmentStatus=READY`
  - 배송 상태 변경 후 `shipmentStatus=SHIPPED`
- `scripts/smoke/e2e-order-invalid-sku-smoke.sh`
  - negative smoke
  - invalid SKU → `FAILED + failureReason=MISSING_SKU[...]`
- `scripts/smoke/e2e-order-address-http-smoke.sh`
  - mock address-api 빌드/배포 → order-api http 모드 전환 → 3개 시나리오 검증 → stub 모드 복원
  - addressId=1 → `status=CREATED, shipmentStatus=READY`
  - addressId=999 → HTTP 404, `ADDRESS_NOT_FOUND`
  - addressId=503 → HTTP 500, `ADDRESS_LOOKUP_FAILED`
- `scripts/smoke/e2e-order-address-real-smoke.sh`
  - real address-api + order-api 연동 e2e 검증 (address-api, address-db, order-api 모두 필요)
  - 주소 생성(userId=9001) → 주문 생성 polling → 주소 soft delete → ADDRESS_NOT_FOUND 차단 확인
  - 6단계: 사전 조건 확인 → real 모드 전환 → port-forward → 주소 생성 → 주문 polling → 삭제 차단 검증

**mock smoke vs real smoke 비교**

| 항목 | mock (`e2e-order-address-http-smoke.sh`) | real (`e2e-order-address-real-smoke.sh`) |
|---|---|---|
| address-api | WireMock (`:mock`) | Spring Boot + PostgreSQL (`:real`) |
| 필요 리소스 | order-api | order-api + address-api + address-db |
| docker 빌드 | 필요 (이미지 빌드 포함) | 불필요 (이미 배포된 real 사용) |
| 시나리오 | 성공/404/503 고정 응답 재현 | 실제 CRUD + soft delete + 주문 차단 |
| 재현성 | 항상 동일 (stub) | DB 상태에 따라 달라질 수 있음 |
| CI 적합성 | self-hosted runner (docker, minikube 필요) | self-hosted runner (minikube 필요) |

Guardrails 검사 항목: `.DS_Store`, `.env`, `secrets/`, 의도하지 않은 `payment-api` 디렉터리, Claude Code 세션 로그, 위험 명령 문자열(`rm -rf`, `DROP TABLE`, `TRUNCATE`, `kubectl delete` 등).

### 로컬 guardrail 자동화

기존 `scripts/claude-guardrails.sh`는 CI에서만 쓰는 스크립트가 아니라, 로컬 Git hook으로도 자동 실행되도록 보강했습니다.

- `.githooks/pre-commit`
  - `git commit` 시 `scripts/claude-guardrails.sh` 자동 실행
- `scripts/install-git-hooks.sh`
  - `git config core.hooksPath .githooks` 설정용 1회 설치 스크립트
---

## 11. 프로젝트 실행 방법

### 인프라 가동

```bash
minikube start
minikube tunnel
kubectl apply -f deployment/
```

### 모니터링 스택 가동

```bash
kubectl apply -f deployment/infra/prometheus.yaml
kubectl apply -f deployment/infra/alertmanager.yaml
kubectl apply -f deployment/infra/grafana.yaml
```

### Prometheus / Grafana 확인

```bash
kubectl port-forward svc/prometheus 9090:9090 -n monitoring
kubectl port-forward svc/grafana 3000:3000 -n monitoring
```

```bash
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[].name'
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {scrapeUrl: .scrapeUrl, health: .health}'
```

기대:
- `rules`에 `logistics-outbox`
- `targets`에 `product-api`, `inventory-api`, `order-api`, `logistics-api`가 `up`

### 서비스 재배포

```bash
./scripts/redeploy-api.sh order-api
./scripts/redeploy-api.sh logistics-api
./scripts/redeploy-api.sh product-api
./scripts/redeploy-api.sh logistics-api 2
```

### 테스트 및 빌드

```bash
cd logistics-api && ./gradlew test
cd order-api && ./gradlew test
cd product-api && ./gradlew test
cd inventory-api && ./gradlew test
```

### 로컬 검증 (port-forward 기반)

**포트 포워드**

```bash
kubectl port-forward svc/order-api-svc 8083:8083 -n ecommerce &
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce &
kubectl port-forward svc/inventory-api-svc 8082:8082 -n ecommerce &
```

**happy path smoke**

```bash
bash scripts/smoke/e2e-order-shipment-smoke.sh
```

검증 내용:
- 1차: `status=CREATED`, `shipmentStatus=READY`
- 2차: `PATCH /shipments/{id}/status` 후 `status=CREATED`, `shipmentStatus=SHIPPED`

> **Polling timeout**: Kafka/Outbox 기반 비동기 처리로 self-hosted runner/minikube 환경에서 30초를 초과할 수 있다. `MAX_WAIT_SECONDS=60`(happy path) / `MAX_WAIT_SECONDS=30`(negative)으로 설정되어 있다. timeout 발생 시 pod 상태, order-api/logistics-api/product-api 로그가 자동 출력된다.

**Troubleshooting — port-forward 충돌**

같은 포트에 이전 실행의 kubectl port-forward가 남아 있으면 smoke script가 자동으로 감지해 kubectl port-forward 프로세스만 종료한다. kubectl port-forward가 아닌 프로세스가 점유 중이면 즉시 실패하고 수동 종료를 안내한다.

```bash
# 수동 정리 (필요 시)
lsof -ti :8083 | xargs kill 2>/dev/null || true
lsof -ti :8084 | xargs kill 2>/dev/null || true
lsof -ti :8090 | xargs kill 2>/dev/null || true
```

**negative smoke (invalid SKU)**

```bash
bash scripts/smoke/e2e-order-invalid-sku-smoke.sh
```

검증 내용:
- `status=FAILED`
- `orderId=null`
- `shipmentStatus=null`
- `failureReason`에 `MISSING_SKU` 포함

**수동 주문 생성 / 상태 조회**

```bash
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 42,
    "items": [
      {
        "sku": "SKU-TEST-001",
        "quantity": 1
      }
    ],
    "shippingAddress": {
      "recipientName": "홍길동",
      "recipientAddress": "서울시 강남구 테헤란로 1"
    }
  }'
```

```bash
curl http://localhost:8083/api/orders/status/<IDEM_KEY>
# → { "status": "CREATED", "shipmentStatus": "READY", ... }
```

**배송지 포함 주문 생성**

```bash
curl -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [
      {
        "sku": "SKU-TEST-001",
        "quantity": 2
      }
    ],
    "shippingAddress": {
      "recipientName": "홍길동",
      "recipientAddress": "서울시 강남구 테헤란로 123, 4층"
    }
  }'
```

**주문 시점 배송지 snapshot 확인**

```bash
kubectl exec -n ecommerce order-db-0 -- psql -U postgres -d orderdb -c "
SELECT id, order_no, recipient_name, recipient_address, created_at
FROM orders
ORDER BY created_at DESC
LIMIT 3;
"
```

**shipment 생성 시 배송지 반영 확인**

```bash
kubectl exec -n ecommerce logistics-db-0 -- psql -U postgres -d logisticsdb -c "
SELECT id, order_id, status, recipient_name, recipient_address, created_at
FROM shipment
ORDER BY created_at DESC
LIMIT 3;
"
```

**배송지 수정 API**

```bash
curl -X PATCH http://localhost:8084/shipments/<SHIPMENT_ID>/address   -H "Content-Type: application/json"   -d '{
    "recipientName": "김철수",
    "recipientAddress": "부산시 해운대구 달맞이길 1"
  }'
```

**배송지 수정 후 shipment 확인**

```bash
kubectl exec -n ecommerce logistics-db-0 -- psql -U postgres -d logisticsdb -c "
SELECT id, order_id, status, recipient_name, recipient_address, updated_at
FROM shipment
WHERE id = <SHIPMENT_ID>;
"
```

**배송지 변경 이력 확인**

```bash
kubectl exec -n ecommerce logistics-db-0 -- psql -U postgres -d logisticsdb -c "
SELECT shipment_id,
       previous_recipient_name,
       previous_recipient_address,
       new_recipient_name,
       new_recipient_address,
       changed_at
FROM shipment_address_history
WHERE shipment_id = <SHIPMENT_ID>
ORDER BY changed_at;
"
```

동일 값 재요청 시 이력 미저장 확인:

```bash
curl -X PATCH http://localhost:8084/shipments/<SHIPMENT_ID>/address \
  -H "Content-Type: application/json" \
  -d '{
    "recipientName": "이영희",
    "recipientAddress": "대전시 유성구 대학로 99"
  }'

kubectl exec -n ecommerce logistics-db-0 -- psql -U postgres -d logisticsdb -c "
SELECT count(*)
FROM shipment_address_history
WHERE shipment_id = <SHIPMENT_ID>;
"
```

기대 결과: 첫 변경 후 `shipment_address_history` 1건 저장, 동일한 값으로 다시 요청하면 count 증가 없음

**order snapshot 불변 확인**

```bash
kubectl exec -n ecommerce order-db-0 -- psql -U postgres -d orderdb -c "
SELECT id, order_no, recipient_name, recipient_address, updated_at
FROM orders
WHERE id = <ORDER_ID>;
"
```


**addressId 기반 주문 생성**

```bash
curl -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}],
    "addressId": 1
  }'
```

**addressId 기반 상태 조회**

```bash
curl http://localhost:8083/api/orders/status/<IDEM_KEY>
# → { "status": "CREATED", "orderId": <ORDER_ID>, "shipmentStatus": "READY", ... }
```

**addressId 기반 orders snapshot 확인**

```bash
kubectl exec -n ecommerce order-db-0 -- psql -U postgres -d orderdb -c "
SELECT id, order_no, status, recipient_name, recipient_address
FROM orders
WHERE id = <ORDER_ID>;
"
```

**addressId 기반 shipment 반영 확인**

```bash
curl http://localhost:8084/shipments/by-order/<ORDER_ID>
```

```bash
kubectl exec -n ecommerce logistics-db-0 -- psql -U postgres -d logisticsdb -c "
SELECT id, order_id, status, recipient_name, recipient_address
FROM shipment
WHERE order_id = <ORDER_ID>;
"
```

**addressId와 shippingAddress 동시 입력 시 addressId 우선**

```bash
curl -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}],
    "addressId": 2,
    "shippingAddress": {
      "recipientName": "직접입력이름(무시되어야함)",
      "recipientAddress": "직접입력주소(무시되어야함)"
    }
  }'
```

기대 결과:
- `orders.recipient_name = 김철수`
- `orders.recipient_address = 부산시 해운대구 달맞이길 2`
- 직접 입력한 `shippingAddress` 값이 아닌 `addressId=2`의 주소가 저장됨

**배송지 누락 시 주문 차단**

```bash
curl -i -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}]
  }'
```

기대 결과:
- HTTP 400
- `errorCode = SHIPPING_ADDRESS_REQUIRED`
- 주문 row 미생성

**존재하지 않는 addressId 차단**

```bash
curl -i -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}],
    "addressId": 999
  }'
```

기대 결과:
- HTTP 404
- `errorCode = ADDRESS_NOT_FOUND`
- 주문 row 미생성

**AddressServiceClient 설정값**

```yaml
address:
  client:
    mode: stub
    base-url: http://address-api:8090
    connect-timeout-ms: 1000
    read-timeout-ms: 2000
```

기본값은 `stub`이며, 평소 로컬/Minikube 검증은 이 설정을 기준으로 동작합니다.

**real address-api 빌드 및 배포 (Spring Boot MVP)**

```bash
kubectl apply -f deployment/infra/db/address-db.yaml
kubectl rollout status statefulset/address-db -n ecommerce --timeout=120s

docker build -t sparta-msa-final-project-address-api:real ./address-api
minikube image load sparta-msa-final-project-address-api:real

kubectl apply -f deployment/address-api/
kubectl rollout status deployment/address-api -n ecommerce --timeout=120s
```

**real address-api 로컬 확인**

```bash
kubectl port-forward pod/<ADDRESS_API_POD> 8090:8080 -n ecommerce

curl -s http://localhost:8090/addresses/1 | jq .
curl -i http://localhost:8090/addresses/999
```

기대 결과:
- `/addresses/1` → `홍길동 / 서울시 강남구 테헤란로 1`
- `/addresses/999` → `404`
- WireMock 헤더(`Matched-Stub-Id`, `Matched-Stub-Name`) 없음

**address-api CRUD 검증**

```bash
# 목록 조회 (userId=1의 활성 주소)
curl -s "http://localhost:8090/addresses?userId=1" | jq .

# 주소 생성 (isDefault=true → 기존 기본 배송지 자동 해제)
curl -s -X POST http://localhost:8090/addresses \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"recipientName":"신규주소","recipientAddress":"대전시 유성구 테크노2로 1","isDefault":true}' | jq .

# 기본 배송지 1개 확인
curl -s "http://localhost:8090/addresses?userId=1" | jq '[.[] | select(.isDefault==true)]'
# 기대: 1개만

# 주소 수정 (recipientName만 변경, 나머지 null → 미수정)
curl -s -X PATCH http://localhost:8090/addresses/2 \
  -H "Content-Type: application/json" \
  -d '{"recipientName":"수정된이름","recipientAddress":null,"isDefault":null}' | jq .

# 주소 삭제 (soft delete)
curl -i -X DELETE http://localhost:8090/addresses/3
# 기대: 204 No Content

# 삭제 후 단건 조회 → 404
curl -i http://localhost:8090/addresses/3

# 삭제된 addressId로 주문 생성 → ADDRESS_NOT_FOUND
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"addressId":3}'
# 기대: HTTP 404, errorCode=ADDRESS_NOT_FOUND
```

**order-api를 real address-api와 연동 검증**

```bash
kubectl set env deployment/order-api -n ecommerce   ADDRESS_CLIENT_MODE=http   ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090

kubectl rollout status deployment/order-api -n ecommerce

curl -i -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}],
    "addressId": 1
  }'

curl http://localhost:8083/api/orders/status/<IDEM_KEY>
# → { "status": "CREATED", "orderId": <ORDER_ID>, "shipmentStatus": "READY", ... }
```

**mock address-api 빌드 및 배포 (address-http smoke 검증용)**

```bash
docker build -f ./address-api/Dockerfile.mock \
  -t sparta-msa-final-project-address-api:mock ./address-api
minikube image load sparta-msa-final-project-address-api:mock
kubectl apply -f deployment/mock-address-api/
kubectl rollout status deployment/mock-address-api -n ecommerce --timeout=120s
```

**mock address-api 로컬 확인**

```bash
kubectl port-forward svc/mock-address-api-svc 8090:8090 -n ecommerce

curl -s http://localhost:8090/addresses/1 | jq .
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/addresses/999
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/addresses/503
```

기대 결과:
- `/addresses/1` → `홍길동 / 서울시 강남구 테헤란로 1`
- `/addresses/999` → `404`
- `/addresses/503` → `503`

**order-api를 http 모드로 임시 전환 (mock 검증용)**

```bash
kubectl set env deployment/order-api -n ecommerce \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://mock-address-api-svc:8090 \
  ADDRESS_CLIENT_CONNECT_TIMEOUT_MS=1000 \
  ADDRESS_CLIENT_READ_TIMEOUT_MS=2000

kubectl rollout status deployment/order-api -n ecommerce --timeout=120s
```

**http 모드 성공 경로 검증 (mock)**

```bash
curl -X POST http://localhost:8083/api/orders   -H "Content-Type: application/json"   -d '{
    "userId": 1,
    "items": [{"sku": "SKU-TEST-001", "quantity": 1}],
    "addressId": 1
  }'

curl http://localhost:8083/api/orders/status/<IDEM_KEY>
# → { "status": "CREATED", "orderId": <ORDER_ID>, "shipmentStatus": "READY", ... }

kubectl exec -n ecommerce order-db-0 -- psql -U postgres -d orderdb -c "
SELECT id, order_no, status, recipient_name, recipient_address
FROM orders
WHERE id = <ORDER_ID>;
"
```

기대 결과:
- `recipient_name = 홍길동`
- `recipient_address = 서울시 강남구 테헤란로 1`

이 과정을 통해 기본 실행은 `stub`로 유지하면서도,
- 필요할 때는 **real `address-api`**와의 실제 연동을 검증할 수 있고
- 반복 가능한 smoke에서는 **mock `address-api`**로 성공/실패 경로를 재현할 수 있습니다.

위 절차를 자동화한 스크립트:

```bash
bash scripts/smoke/e2e-order-address-http-smoke.sh
```

스크립트는 이미지 빌드부터 stub 모드 복원까지 전 과정을 처리하며, 실패 시에도 `trap`으로 stub 모드를 복원합니다.

**real address-api smoke (order-api + address-api + address-db 연동 검증)**

`address-api`(`:real`), `address-db`, `order-api` 가 모두 `ecommerce` namespace에 배포된 상태에서 실행합니다.

```bash
bash scripts/smoke/e2e-order-address-real-smoke.sh
```

스크립트는 다음 6단계를 자동으로 처리합니다.

1. `kubectl`/`curl` 및 필수 Deployment 존재 여부 확인
2. `order-api` 를 real http 모드로 전환 (`ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090`)
3. `order-api`(8083) + `address-api-svc`(8090) port-forward 시작 및 readiness 대기
4. `POST /addresses` — smoke 전용 userId=9001 주소 생성, `id` 추출
5. `POST /api/orders` — 생성된 `addressId` 로 주문 → `status=CREATED, shipmentStatus=READY` polling
6. `DELETE /addresses/{id}` → 204, `GET /addresses/{id}` → 404, 주문 재시도 → HTTP 404 `ADDRESS_NOT_FOUND` 확인

실패 시에도 `trap`으로 order-api env를 복원하고 port-forward를 종료합니다. real address-api smoke는 GitHub-hosted runner에서 직접 실행할 수 없으며, minikube + address-db 가 준비된 self-hosted runner 또는 로컬 환경에서 실행합니다.

**배송 상태 변경**

```bash
curl http://localhost:8084/shipments/by-order/<ORDER_ID>
# → shipmentId 확인

curl -X PATCH http://localhost:8084/shipments/<SHIPMENT_ID>/status \
  -H "Content-Type: application/json" \
  -d '{"status": "SHIPPED"}'
```

**주문 배송 상태 반영 확인**

```bash
curl http://localhost:8083/api/orders/status/<IDEM_KEY>
# → { "status": "CREATED", "shipmentStatus": "SHIPPED", ... }
```

**메트릭 조회**

```bash
curl -s http://localhost:8084/actuator/prometheus | grep "logistics_outbox"
curl -s http://localhost:8083/actuator/prometheus | grep "order_outbox"
curl -s http://localhost:8082/actuator/prometheus | grep "inventory_outbox"
```

**admin retry / stale recovery 메트릭 확인**

```bash
curl -s http://localhost:8084/actuator/prometheus | grep "admin_retry"
curl -s http://localhost:8084/actuator/prometheus | grep "outbox_stale"
```

### Guardrails 로컬 자동화 (Git hook)

클론 후 한 번만 실행하면 이후 `git commit` 시 guardrails가 자동 실행됩니다.

```bash
bash scripts/install-git-hooks.sh
# → [OK] Git hooks 경로가 .githooks 로 설정되었습니다.
```

설치 후에는 `git commit` 시 아래처럼 자동으로 실행됩니다.

```text
── Claude guardrails (pre-commit) ──────────────────────────
Claude guardrails passed.
────────────────────────────────────────────────────────────
```

guardrails가 실패하면 커밋이 중단됩니다. 실패 원인을 해결한 뒤 다시 커밋하세요.

```text
[FAIL] .env 또는 secret 파일이 커밋 대상에 포함되어 있습니다.
```

**수동 실행** (hook 없이 확인만):

```bash
git add <커밋할 파일>
bash scripts/claude-guardrails.sh
# → Claude guardrails passed.
```

**우회** (긴급 상황만, 권장하지 않음):

```bash
git commit --no-verify
```

### GitHub Actions 수동 실행

`workflow_dispatch` 기반 워크플로는 GitHub Actions 화면에서 수동 실행합니다.

**Smoke Tests**
- `target=happy`
- `target=negative`
- `target=address-http`
- `target=all`

**Integration Tests**
- `service=product-api`
- `service=order-api`
- `service=inventory-api`
- `service=logistics-api`
- `service=all`

권장 실행 순서:
1. `Smoke Tests` → `happy`
2. `Smoke Tests` → `negative`
3. `Integration Tests` → 개별 서비스
4. 마지막에 `Integration Tests` → `all`

---

## 12. 트러블슈팅

### shipmentStatus가 null로 조회될 때

확인 순서:

1. **order-api 상태 조회 확인**
   ```bash
   curl http://localhost:8083/api/orders/status/{idemKey}
   # shipmentStatus가 null이면 아래 단계로 이동
   ```

2. **outbox_event 적체 확인** — logistics-api DB에서
   ```sql
   SELECT status, count(*) FROM outbox_event GROUP BY status;
   -- PENDING 또는 PROCESSING이 누적되면 발행 지연
   ```

3. **stale PROCESSING 확인**
   ```sql
   SELECT id, status, next_retry_at FROM outbox_event
   WHERE status = 'PROCESSING' AND next_retry_at < now();
   -- 해당 행이 있으면 StaleOutboxRecoveryJob(60s) 대기 또는 직접 PENDING으로 UPDATE
   ```

4. **pg_stat_activity 확인**
   ```sql
   SELECT pid, state, wait_event, query FROM pg_stat_activity
   WHERE state = 'idle in transaction';
   -- idle in transaction이 장시간 지속되면 outbox publisher가 잠금을 점유 중
   ```

5. **메트릭 확인**
   ```bash
   curl -s http://localhost:8084/actuator/prometheus | grep "logistics_outbox_publish_total"
   curl -s http://localhost:8084/actuator/prometheus | grep "logistics_outbox_events"
   curl -s http://localhost:8083/actuator/prometheus | grep "order_shipment_event_consume_total"
   ```

6. **Kafka consumer lag 확인**
   ```bash
   kubectl exec -n ecommerce <kafka-pod> -- \
    kafka-consumer-groups.sh --bootstrap-server <broker>:9092 \
    --describe --group order-api
   ```

### invalid SKU 주문이 실패 처리되지 않을 때

1. **status API 응답 확인**
   ```bash
   curl http://localhost:8083/api/orders/status/{idemKey}
   # 기대: status=FAILED, failureReason=MISSING_SKU=[...]
   ```

2. **product-api 실패 reply payload 확인**
  - `idemKey`, `userId`, `requestItem`가 null 이 아닌지
  - 실패 reply에도 correlation field가 유지되는지

3. **order-api OrderEventConsumer 로그 확인**
  - `success=false` 또는 `error!=null` 분기에서 `failOrder()` 호출 여부

4. **negative smoke 재실행**
   ```bash
   bash scripts/smoke/e2e-order-invalid-sku-smoke.sh
   ```

### FAILED outbox_event 수동 재처리

운영자가 FAILED 이벤트를 수동으로 다시 살릴 수 있도록 admin retry API를 제공합니다.

```bash
# FAILED 이벤트 조회
curl -s "http://localhost:8084/admin/outbox?status=FAILED" | jq .

# 단건 재처리
curl -s -X POST http://localhost:8084/admin/outbox/<ID>/retry | jq .

# 배치 재처리
curl -s -X POST "http://localhost:8084/admin/outbox/retry?limit=20" | jq .

# 메트릭 확인
curl -s http://localhost:8084/actuator/prometheus | grep "admin_retry"
```

예상 흐름:
- `FAILED -> PENDING`
- 이후 publisher가 다시 집어가면 최종적으로 `SENT`

### terminal FAILED 판단과 운영 기준

최대 재시도 초과로 terminal `FAILED`에 도달하면 `[OutboxTerminal]` WARN 로그를 남기도록 보강했습니다. 현재는 DLQ를 즉시 도입하지 않고, **FAILED 상태 + admin retry + Prometheus alert + runbook** 조합으로 운영합니다.

확인 포인트:
- `GET /admin/outbox?status=FAILED`
- `logistics_outbox_events{status="FAILED"}`
- `logistics_outbox_stale_high_retry_total`
- `docs/runbooks/logistics-outbox-alerts.md`
- `docs/adr/001-outbox-dlq-decision.md`

### Stale PROCESSING Recovery가 필요한 이유

`OutboxPublisherJob`이 PROCESSING 상태로 전환한 후 Kafka send를 완료하기 전에 Pod가 비정상 종료되면 해당 행은 영구적으로 PROCESSING 상태로 남습니다. `next_retry_at`에 기록된 claim 만료 시각(`claim 시점 + 2분`)을 기준으로 `StaleOutboxRecoveryJob`(60초 주기)이 PENDING으로 복구합니다.

복구 후 `retry_count`가 증가하며, 최대 재시도 횟수(현재 5회) 초과 시 FAILED로 전환됩니다.

---

## 13. 향후 개선 과제

### 도메인 / 운영
- 사용자 주소 서비스 고도화
  - 인증 연계 시 userId 헤더 기반 검증으로 전환 (현재는 request.userId 신뢰)
  - 기본 배송지 동시 변경 시 race condition 테스트 보강 → Testcontainers(PostgreSQL) 기반 통합 테스트로 완료됨 (아래 완료 항목 참조)
  - addressId와 shippingAddress 동시 입력 정책을 장기적으로 단일 방식으로 단순화할지 검토
- 배송지 변경 이력 관리 고도화
  - ~~상태 변경 이력과 주소 변경 이력의 분리 또는 통합 조회 방식 검토~~ → 완료 ([ADR-004](docs/adr/004-shipment-history-timeline-query.md): 저장 분리 유지, 통합 timeline API는 CS/운영 요구 구체화 후 후속 구현)
  - 배송 이력 통합 timeline API 구현 (관리자 권한/응답 스키마 확정 후): `GET /admin/shipments/{shipmentId}/timeline` — read-only projection으로 두 이력 병합
  - ~~주소 변경 주체(user/system) 및 변경 사유(reason) 저장 여부 검토~~ → 완료 ([ADR-003](docs/adr/003-address-history-actor-reason.md): 인증 시스템 도입 전 구현 보류 — actorType은 인증 없이 판별 불가, reason은 API 계약 변경 필요)
  - actorType·reason 실제 구현 (인증 시스템 도입 + API 계약 변경 후): Flyway migration + 기존 이력 UNKNOWN 채우기
- address-api 이력 고도화
  - ~~관리자용 전체 이력 조회/검색 API (userId 무관, 날짜 범위 필터 등)~~ → 완료
  - ~~이력 보존 기간 정책 정리~~ → 완료 ([ADR-002](docs/adr/002-address-history-retention-policy.md): 현재 무기한 보존 유지, 정책 기준 확정 시 자동 삭제 또는 아카이빙 전환 예정)
  - 정식 인증 연계 시 `X-Admin-Api-Key` 임시 가드를 Spring Security 기반 관리자 권한 검증으로 전환
  - 이력 보존 기간 정책 실제 구현 (보존 기간 N 확정 후): 자동 삭제 배치 또는 아카이브 테이블 이동
- Outbox retry 정책 추가 고도화
  - ~~영구 실패와 재시도 가능 실패의 코드 레벨 구분 검토~~ → 완료 (`NonRetryableOutboxException` 도입 — 미등록 eventType 등 영구 실패는 `markPermanentFailed()`로 retryCount 증가 없이 즉시 FAILED 처리, Kafka 일시 장애 등 재시도 가능 실패는 기존 지수 백오프 유지)
  - DLQ 재검토 기준 도달 시 DB 기반 DLQ 도입
- `logistics_outbox_events` Gauge 부하 고려
  - scrape 간격이 더 짧아지는 환경에서는 전용 스케줄러 기반 캐시 갱신 구조 검토
- Prometheus alert rule 실제 운영 적용
  - Alertmanager / Slack / PagerDuty 연동
  - FAILED / high-retry / stale recovery 기준의 알림 임계치 튜닝

### Claude Code 하네스 / 자동화
- self-hosted runner 운영 안정화
  - runner 장애/오프라인 감지 기준 정리
  - smoke / integration 수동 실행 결과 문서화
- workflow 결과 요약 자동화
  - 서비스별 test/integration/smoke 실행 결과를 README 또는 runbook에 연결

### 완료된 항목
- ~~실제 Minikube/Kubernetes 환경에서 `logistics-api` 배포 검증~~
- ~~`shipment-event`를 `order-api`가 수신해 주문 배송 상태에 반영하는 흐름 추가~~
- ~~`logistics-api` 운영 지표 추가 (Micrometer 기반 메트릭)~~
- ~~`.claude/settings.json` 권한 경계 추가~~
- ~~`docs/claude-feedback-log.md` 기반 피드백 루프 기록~~
- ~~hooks 기반 자동 guardrail 추가~~
- ~~전체 서비스 테스트 matrix CI 확장~~
- ~~smoke workflow를 self-hosted runner에 연결~~
- ~~happy / negative smoke script 추가~~
- ~~product snapshot 실패 응답 처리 및 correlation field 보강~~
- ~~전체 서비스 Outbox 상태 통합 모니터링~~
- ~~Stale PROCESSING recovery 정책 정교화~~
- ~~FAILED outbox_event 수동 재처리 채널 보강~~
- ~~Prometheus alert rule / runbook 초안 추가~~
- ~~DLQ 필요성 판단 ADR 추가~~
- ~~주문 시점 배송지 snapshot 저장 (`orders.recipient_name`, `orders.recipient_address`)~~
- ~~OrderCreatedEvent를 통한 배송지 정보 전달~~
- ~~배송지 수정 API 추가 (`PATCH /shipments/{shipmentId}/address`)~~
- ~~배송지 변경 이력 저장 (`shipment_address_history`) 및 동일 값 재요청 시 중복 미저장 검증~~
- ~~`READY` 상태에서만 배송지 수정 허용 및 order snapshot 불변성 검증~~
- ~~addressId 기반 주문 배송지 해소 추가 (`CreateOrderRequest.addressId`, `AddressServiceClient`, `StubAddressServiceClient`)~~
- ~~addressId 우선 / shippingAddress fallback / 배송지 누락 400 / 잘못된 addressId 404 검증~~
- ~~AddressServiceClient를 설정 기반(stub\|http)으로 분리하고 HTTP 구현체/예외 매핑 준비~~
- ~~WireMock 기반 mock `address-api` 추가 및 `mode=http` 검증 환경 구성~~
- ~~`addressId=1` 성공 경로와 `999 -> ADDRESS_NOT_FOUND`, `503 -> ADDRESS_LOOKUP_FAILED` 검증~~
- ~~address-http smoke script 자동화 및 smoke-tests.yml 연결 (7단계 전체 검증)~~
- ~~integration-tests.yml 서비스별 test/integrationTest 분기 구성~~
- ~~Spring Boot 기반 real `address-api` MVP 추가 (`GET /addresses/{id}`, PostgreSQL, Flyway, actuator probe)~~
- ~~real `address-api` 배포 및 `order-api` http 모드 연동 수동 검증 완료 (`addressId=1 -> CREATED/READY`)~~
- ~~real / mock address-api 이미지 태그 분리 (`address-api:real` / `mock-address-api:mock`, 배포 경로 및 Service 이름까지 완전 분리)~~
- ~~real address-api CRUD API 추가 (GET /addresses?userId, POST, PATCH, DELETE soft delete, partial unique index, Bean Validation)~~
- ~~real address-api smoke 자동화 (`e2e-order-address-real-smoke.sh` — 주소 생성 → 주문 polling → 삭제 → ADDRESS_NOT_FOUND 차단 검증)~~
- ~~주소 소유자 검증 추가 (`GET /addresses/{id}?userId=` 소유자 확인, 불일치 시 `ADDRESS_NOT_FOUND` 차단, smoke 7단계로 자동 검증)~~
- ~~기본 배송지 1개 정책 동시성 검증 추가 (Testcontainers + PostgreSQL partial unique index 기반, 순차/동시 시나리오, partial index 위반 시 409 Conflict 매핑)~~
- ~~사용자 주소 변경/삭제 이력 저장 (`user_address_history`, V4 Flyway) — CREATE/UPDATE/DELETE 이력 append-only, 동일 값 UPDATE 미저장, 이력 저장 실패 시 주소 변경도 롤백~~
- ~~주소 변경 이력 조회 API 추가 (`GET /addresses/{id}/histories?userId=`) — 소유자 검증, deleted 주소 조회 허용, changedAt DESC 정렬~~
- ~~기본 배송지 자동 해제 이력 저장 — 새 기본 배송지 지정 시 기존 default 주소의 isDefault 변경을 actionType=UPDATE 이력으로 저장. CREATE/UPDATE 모두 적용~~
- ~~주소 변경 이력 조회 API 페이징/actionType 필터 추가 (page/size/actionType, 최대 size=100, 잘못된 값 400)~~
- ~~사용자 기본 배송지 조회 API 추가 (`GET /addresses/default?userId=`) — userId 필수(400), 기본 배송지 없으면 404, deleted 제외~~
- ~~사용자 주소 목록 페이징 조회 API 추가 (`GET /addresses/page?userId=&page=&size=`) — deleted=false, isDefault DESC/id DESC 정렬, size 최대 100, 잘못된 값 400~~
- ~~관리자용 전체 이력 조회/검색 API 추가 (`GET /admin/addresses/histories`) — userId/addressId/actionType/날짜 범위 optional 필터, createdAt DESC, size 최대 100~~
- ~~관리자 이력 API X-Admin-Api-Key 접근 통제 추가 — 헤더 누락/불일치/설정값 미지정 시 403~~
- ~~user_address_history 이력 보존 기간 정책 ADR 작성 ([ADR-002](docs/adr/002-address-history-retention-policy.md)) — 현재 D안(무기한 보존 + 정책 보류) 채택, 후속 구현 기준 정의~~
- ~~이력 보존 정책 드라이런 조회 API 추가 (`GET /admin/addresses/histories/retention-dry-run?retentionMonths=N`) — read-only, 실제 삭제 없음, 1≤N≤120~~
- ~~주소 변경 주체(actorType)·변경 사유(reason) 저장 여부 정책 검토 ([ADR-003](docs/adr/003-address-history-actor-reason.md)) — 인증 시스템 도입 전 보류 결정~~
- ~~배송 상태 이력과 배송지 변경 이력 분리/통합 조회 정책 검토 ([ADR-004](docs/adr/004-shipment-history-timeline-query.md)) — 저장 분리 유지, 통합 timeline API 후속 구현 보류 결정~~
- ~~Outbox 영구 실패/재시도 가능 실패 코드 레벨 구분 구현 — `NonRetryableOutboxException` 도입, `markPermanentFailed()` 추가, `OutboxPublisherJob` catch 블록 분리~~
