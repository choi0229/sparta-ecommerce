# sparta-msa-final-project

# (E-Commerce) sparta-msa-ecommerce



## 1. 프로젝트 개요

e-commerce에서 가장 기본이 되는 것은 정합성 및 일관성이라고 생각했다. 그렇기에 본 프로젝트는 **e-commerce 플랫폼의 핵심 도메인(Product / Order / Inventory / Coupon)** 을 **MSA 아키텍처** 관점에서 설계하고,

**분산 트랜잭션(Saga), 이벤트 기반 통신(Kafka), 정합성/일관성 보장(멱등성, TTL 자동해제, 커밋 이후 발행)** 을 중심으로

“실무에서 터지는 문제(중복 이벤트/재고 오버셀/유실된 예약/가격 변경)”를 **재현하고 해결**하는 것을 목표로 한다.



- 핵심 키워드: **SKU(Variant) 기반 상품 모델링**, **Order–Inventory Saga**, **OrderItem Snapshot**

- 고도화 키워드: **커밋 이후 이벤트 발행**, **멱등성**, **Saga 상태 저장**, **TTL 기반 자동해제**

- 확장 키워드: **Outbox / DLQ / Replay / Lock 고도화**



---



## 2. 서비스 구성 (MSA)

- **product-service**

- **order-service**

- **inventory-service**



---



## 3. 기술 스택

- **Backend**: Spring Boot, Spring Data JPA, Validation, Lombok

- **DB**: PostgreSQL (JSONB를 활용하기 위해)

- **Messaging**: Kafka

- **Cache/Key-Value**: Redis (멱등성 키 활용)

- **Observability**: Prometheus/Grafana, ELK, Zipkin



---



## 4. 개발 기능 정의 (MVP)

| 구분 | 구현 내용 | 상세 설명 |
|---|---|---|
| **Must-Have (핵심 기능)** | 상세한 상품 옵션 관리 | 색상, 사이즈 등 복잡한 상품 옵션을 **SKU(Variant) 단위**로 정확하게 관리 |
|  | 안전한 주문-재고 연동 | 주문이 들어오면 재고를 **즉시 예약/점유**하고, 실패 시 **자동 복구(Saga 보상)** 로 정합성 보장 |
|  | 주문 당시 정보 보존 | 상품 가격/옵션이 바뀌어도 **주문 당시 정보(가격/상품명/옵션)를 Snapshot**으로 저장해 그대로 유지 |
| **Should-Have (주요 기능)** | 중복 주문/결제 방지 | 동일 요청/이벤트가 중복으로 들어와도 **1회만 처리(멱등성)** 하도록 방어 |
|  | 결제 대기 재고 자동 회수 | 결제하지 않고 이탈한 주문의 재고를 **TTL(expires_at) + 스케줄러**로 자동 해제/원복 |
|  | 주문 단계 실시간 추적 | 주문이 현재 어느 단계(재고예약/결제대기/완료 등)인지 **Saga 상태를 DB에 기록**하여 추적 가능 |
| **Could-Have (부가 기능)** | 선착순 이벤트 대응 | 수만 명 동시 접근에서도 오버셀 방지를 위해 **락 고도화(분산락 등)** 적용 |
|  | 데이터 유실 방지 시스템 | DB 저장은 됐는데 이벤트 발행이 실패하는 상황을 막기 위해 **Transactional Outbox** 도입 |


---

## 5. 논리 ERD
```erDiagram
PRODUCT ||--o{ PRODUCT_VARIANT : has
CATEGORY ||--o{ CATEGORY : parent_child
CATEGORY ||--o{ PRODUCT : classifies

ORDERS ||--o{ ORDER_ITEM : contains

INVENTORY_STOCK ||--o{ INVENTORY_RESERVATION_ITEM : reserved_for
INVENTORY_RESERVATION ||--o{ INVENTORY_RESERVATION_ITEM : has

COUPON_POLICY ||--o{ COUPON_SCOPE : applies_to
COUPON_POLICY ||--o{ USER_COUPON : issued
USER_COUPON ||--o{ COUPON_RESERVATION : reserved_by
```
---

## 6. 진행상황

### 인프라 (docker-compose)
- PostgreSQL: productdb
- Kafka/Zookeeper
- MinIO: bucket init(product-images)
- Observability: Prometheus/Grafana
- Search/Log: Elasticsearch/Kibana/Logstash

### DB (Flyway, product-api)
- category: parent_id 기반 트리 구조
- product
- product_variant: SKU, option_json(jsonb)
- product_image: storage_key, url, type, sort_order, is_primary

### 구현 완료 (product-api)
- Category: 트리 조회 / 생성·수정·삭제(soft delete)
- Product(Admin): 상품 등록(Product+Variant+Image 메타), SKU 중복 검증, 이미지 추가(대표 이미지 규칙)
- Image: 업로드 → MinIO 저장 → storageKey/url 반환

### 서비스 연동 (Kafka)
- product-api → order-api: 상품 등록 시 ProductVariant 이벤트 발행
- order-api: 이벤트 수신 후 product_projection 적재(주문 스냅샷 기반)

### 진행 중 (order-api)
- 주문 생성/사가(Order–Inventory) 구현 중
