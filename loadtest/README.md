# k6 Load Test

Order API 부하 테스트용 k6 스크립트 모음입니다.

## 폴더 구조


loadtest/k6/
scripts/
create-order.js
hot-sku.js
distributed-sku.js
data/
skus.csv
run.sh
README.md


## 사전 준비(중요)

### 1) SKU 데이터 준비
- `product-api` DB에 SKU/Variant가 존재해야 합니다. (동기 snapshot 조회 or projection 방식에 따라 필요 데이터가 달라질 수 있음)
- `inventory-api`에서 예약이 성공하려면 `inventory_stock`에도 SKU 재고 row가 있어야 합니다.

### 2) 서버 기동
- order-api: `http://localhost:8080` (기본값)
- Kafka / outbox publisher / inventory consumer 등 파이프라인이 정상 작동해야 합니다.

## 실행 방법

### 기본 주문 부하

BASE_URL=http://localhost:8080
./run.sh create


### 핫 SKU 경합

HOT_SKU=SKU-001 BASE_URL=http://localhost:8080
./run.sh hot


### 분산 SKU (CSV)

SKU_CSV=./data/skus.csv ITEMS_PER_ORDER=2 BASE_URL=http://localhost:8080
./run.sh dist


## 테스트 관측 포인트(권장)

### API 레벨
- p95/p99 latency
- error rate

### Kafka
- consumer lag (inventory/order/payment 관련 group)
- produce error / retry

### DB (Postgres)
- outbox_event PENDING 개수 추이(쌓이면 outbox publisher 병목)
- inventory_stock row lock 경합 (핫 SKU에서 p99 튐)
- 예약 만료(TTL) 처리량(별도 워커)

## 스크립트 설명

- `create-order.js`
    - SKU 2개 고정 주문
    - 가장 기본적인 E2E 흐름 확인

- `hot-sku.js`
    - 동일 SKU만 반복 주문
    - 락 경합 / oversell 방지 / 지연 확인

- `distributed-sku.js`
    - CSV SKU 풀에서 랜덤으로 SKU 선택
    - 경합을 줄이고 "순수 처리량" 확인


---

# 주문 E2E 부하 테스트

`POST /api/orders`의 접수부터 `GET /api/orders/status/{idemKey}`의 최종 상태까지 측정합니다. 스크립트는 `k6/scripts/create-order-e2e.js`입니다.

## 사전 준비

### 1) 서비스 기동

order-api, inventory-api, product-api, Kafka, 각 PostgreSQL을 실행합니다. 측정값에 로그 비용이 섞이지 않도록 세 서비스 모두 `loadtest` 프로필로 실행합니다. 이 프로필은 `show-sql`과 Kafka·트랜잭션 DEBUG 로그를 끕니다.

```bash
SPRING_PROFILES_ACTIVE=loadtest java -jar build/libs/<service>.jar
```

logistics-api는 주문 완료에 필요하지 않습니다. 실행하면 `order-create-event` 처리와 `shipment-event` 쓰기가 더해지므로, 실행 여부를 결과에 함께 기록합니다.

서비스를 재시작한 직후에는 Kafka 컨슈머 그룹의 파티션 할당이 끝날 때까지 이벤트가 처리되지 않습니다(Smoke 환경에서 재시작 후 약 30초 소요). 이 구간의 주문은 `orders_timeout`으로 집계되므로, 모든 그룹에 파티션이 할당된 뒤 측정을 시작합니다.

```bash
kafka-consumer-groups --bootstrap-server <broker> --describe --group order-api
kafka-consumer-groups --bootstrap-server <broker> --describe --group inventory-api
kafka-consumer-groups --bootstrap-server <broker> --describe --group product-api
```

### 2) 상품·재고 시드

> 시드는 데이터를 추가합니다. 공용 환경이라면 실행 전에 확인하세요. 기존 데이터를 지우는 스크립트는 제공하지 않습니다.

```bash
# 처음 한 번: 상품과 재고를 실제 API 경로로 생성
STOCK=20000 PRODUCT_API=http://localhost:8081 ./seed/seed-products.sh

# 재고만 늘릴 때 (inventorydb)
psql -v skus='LOADTEST-SKU-A,LOADTEST-SKU-B' -v add=20000 -f seed/seed-stock.sql
```

**필요한 재고:** 주문 1건이 SKU별로 `QUANTITY`개를 사용합니다. `ramp`는 총 4,875건(부하 단계에서 계산한 값)이고, `constant`는 `RATE × 지속 시간(초)`입니다. 결제 실패 건의 예약은 TTL(30분) 동안 재고를 점유하므로 넉넉히 준비합니다.

## 실행

```bash
cd loadtest/k6

# create-order2.js와 같은 부하 단계 (최대 50 RPS)
RUN_ID=ramp1 PROFILE=ramp ./run.sh e2e

# 고정 부하: 10 → 20 → 30 → 50 순서로 한 단계씩 실행해 Outbox 처리 상한을 확인
RUN_ID=rps10 PROFILE=constant RATE=10 DURATION=2m ./run.sh e2e
```

한 단계가 끝나면 Outbox 적체가 사라지고 모든 주문이 끝난 뒤 다음 단계를 실행합니다. 실행 환경 변수는 다음과 같습니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `RUN_ID` | 실행 시각 | 이번 테스트 식별자. 멱등키 접두사가 되어 정합성 검증에 사용됩니다. 영문·숫자·하이픈만 사용하고 실행마다 새 값을 씁니다 |
| `PROFILE` | `ramp` | `ramp`(ramping-arrival-rate), `constant`(constant-arrival-rate) |
| `RATE` / `DURATION` | `10` / `2m` | `constant` 프로필의 RPS와 지속 시간 |
| `SKUS` / `QUANTITY` | `LOADTEST-SKU-A,LOADTEST-SKU-B` / `1` | 주문 항목. `QUANTITY`를 재고보다 크게 하면 재고 부족 시나리오입니다 |
| `DUPLICATE_SEND` | `false` | `true`면 같은 키로 동시에 2건을 보내 두 응답을 비교합니다 |
| `E2E_BASE_URL` | `http://localhost:8083/api` | order-api 주소 |

## 시나리오

| 시나리오 | 설정 | 기대 결과 |
|---|---|---|
| 정상 (결제 100%) | order-api 기본값 | `orders_failed`가 0에 가까움 |
| 결제 90% | order-api에 `PAYMENT_MOCK_SUCCESS_RATE=90` | 약 10%가 `orders_failed`, 예약은 TTL 전까지 유지 |
| 재고 부족 | 재고보다 많은 주문 또는 `QUANTITY` | `orders_failed` 증가, 재고 초과 확정 없음 |
| 같은 키 동시 요청 | `DUPLICATE_SEND=true` | `duplicate_response_mismatches` 0, 멱등키당 주문 1건 |

## 측정 지표

| 지표 | 의미 |
|---|---|
| `orders_accepted` | `202`로 접수된 주문 |
| `orders_rejected` | `202`가 아닌 응답을 받은 주문 |
| `orders_succeeded` / `orders_failed` | 폴링으로 확인한 최종 성공·실패 |
| `orders_timeout` | **15초 안에 최종 상태를 관측하지 못한 주문.** 주문 실패가 아니며, 이후에 완료될 수 있습니다 |
| `status_poll_errors` | 상태 조회가 `200`이 아닌 횟수 |
| `order_e2e_duration` | 접수 요청 시작부터 최종 상태 관측까지 걸린 시간. `outcome` 태그로 성공·실패를 구분합니다 |
| `dropped_iterations` | 목표 부하를 넣지 못한 횟수. **0이 아니면 threshold 실패로 처리되며, 그 실행의 지연 수치는 비교에 쓰지 않습니다** |

## 결과 해석

- **E2E 지연에는 폴링 간격만큼(최대 약 1초)의 관측 지연이 포함됩니다.** 실제 완료 후 다음 폴링까지 기다린 시간이 더해지므로, 1초 미만의 차이는 의미를 두지 않습니다. `order_e2e_duration`은 타임아웃 건을 포함하지 않으므로 `orders_timeout`과 함께 봅니다.
- **`maxVUs`는 `목표 RPS × (15초 + 여유 5초)`입니다.** 주문마다 VU가 최종 상태까지 점유되므로, 처리가 늦어질수록 필요한 VU가 늘어납니다. 그래도 `dropped_iterations`가 발생하면 부하 발생기나 서버가 한계입니다.
- **코드상 Outbox 처리 상한:** order-api Publisher는 500ms마다 최대 50건을 순차 발행하므로 최대 약 100건/초입니다. 성공 주문 1건당 5건을 발행해 약 20주문/초가 상한입니다(계산값이며 측정값이 아닙니다). 이를 넘는 구간에서는 적체와 `orders_timeout` 증가가 예상됩니다.
- 결과 보고 형식: 접수 건수, 최종 성공·실패·타임아웃 건수, E2E p50/p95/p99, `dropped_iterations`, 정합성 검증 결과.
