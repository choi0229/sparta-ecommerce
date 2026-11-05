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

# DB 정합성 검증

주문 DB와 재고 DB가 분리되어 있어 각각 검증하고, `verify.sh`가 결과를 합칩니다. 이번 테스트의 주문은 멱등키 접두사(`<RUN_ID>-`)로 식별합니다. 검증 스크립트는 데이터를 변경하지 않으며, 세션 임시 객체만 사용합니다.

## 실행 순서

`docker compose` 프로젝트 디렉터리에서 실행하는 것을 기본으로 합니다. 다른 환경이면 `ORDER_PSQL`, `INVENTORY_PSQL`, `PRODUCT_PSQL`, `KAFKA_GROUPS` 환경 변수로 명령을 바꿉니다.

```bash
# 1. 측정 전: SKU별 재고 스냅샷 저장 (inventorydb에 loadtest_stock_snapshot 테이블을 만듭니다)
docker compose exec -T inventory-db psql -U root -d inventorydb \
  -v run_id=rps10 -v skus='LOADTEST-SKU-A,LOADTEST-SKU-B' < loadtest/sql/before.sql

# 2. 측정 중: 다른 터미널에서 Outbox 적체와 컨슈머 지연 기록
INTERVAL=5 ./loadtest/outbox-watch.sh > loadtest/results/rps10-outbox.csv

# 3. 부하 실행
(cd loadtest/k6 && RUN_ID=rps10 PROFILE=constant RATE=10 DURATION=2m ./run.sh e2e)

# 4. 모든 주문이 끝난 뒤 검증. 종료 코드 2(WAIT)면 잠시 후 다시 실행
./loadtest/verify.sh rps10
```

## 판정

| 판정 | 뜻 | 종료 코드 |
|---|---|---|
| `PASS` | 항목 통과 | 전체 통과 시 0 |
| `FAIL` | 정합성 위반. 원인을 확인할 때까지 수치를 신뢰하지 않습니다 | 1 |
| `WAIT` | 처리 중인 주문이나 Outbox 적체가 남아 있어 판단 보류 | 2 |
| `INFO` | 참고 집계 | 영향 없음 |

## 검사 항목

| 항목 | 내용 |
|---|---|
| `stock.total_conservation.<sku>` | 초기 total = 현재 total + 이번 테스트의 확정 수량 |
| `stock.reserved_conservation.<sku>` | 현재 reserved = 초기 reserved + 이번 테스트에서 남은 `RESERVED` 수량 |
| `stock.invariant_total_ge_reserved_ge_0` | total ≥ reserved ≥ 0 |
| `reservation.completed_order_not_confirmed` | SUCCEEDED(주문 `COMPLETED`) 주문의 예약이 `CONFIRMED`인지 |
| `reservation.confirmed_but_order_failed` | 재고가 확정됐는데 주문이 실패로 끝난 건 |
| `order.duplicate_order_no` | 주문번호 중복 |
| `order.order_shared_by_keys` | 한 주문을 여러 멱등키가 가리키는 건. `idem_key`가 PK라 키 중복은 원래 불가능하므로 이 방향으로 검사합니다 |
| `order.orphan_orders` | 테스트 시간대의 주문 중 고객 멱등 기록이 없는 건. `shipment-event:` 기록은 제외합니다 |
| `order.failed_request_without_reason`, `order.failed_order_without_reason` | 실패 사유 누락 |
| `order.saga_mismatch` | 주문과 Saga 상태 불일치 |
| `order.amount_mismatch`, `order.payment_amount_mismatch` | 주문 총액과 항목 합계, 결제 금액 불일치 |
| `order.completed_without_payment_success`, `order.declined_but_progressed` | 결제 결과와 주문 상태 불일치 |
| `*.outbox_failed`, `*.outbox_backlog` | 재시도를 소진한 Outbox 행(`FAIL`), 미발행 적체(`WAIT`) |

## 결과 해석과 주의사항

- **`reservation.pending_ttl`은 위반이 아닙니다.** 결제 실패 주문은 예약 해제 보상이 없어 TTL(30분)이 지날 때까지 `RESERVED`로 남고 재고를 점유합니다. 그래서 결제 90% 시나리오에서는 reserved가 0으로 돌아오지 않으며, 재고 보존 검사는 남은 `RESERVED` 수량을 반영해 판단합니다. TTL이 지난 뒤 다시 실행하면 `EXPIRED`로 바뀌고 reserved가 줄어듭니다.
- **`reservation.expired_not_released`가 `WAIT`**이면 만료 스케줄러가 아직 처리하지 않은 건입니다. 계속 남으면 inventory-api 만료 작업을 확인합니다.
- **같은 SKU에 다른 주문이 섞이면 재고 보존 검사가 `FAIL`이 될 수 있습니다.** 측정 중에는 다른 트래픽을 넣지 말고, 실행마다 새 `RUN_ID`를 씁니다. 이전 실행의 `RESERVED` 예약이 측정 중에 만료되어도 reserved가 달라지므로, 앞선 실행의 예약이 남아 있으면 TTL이 지난 뒤 측정하거나 SKU를 분리합니다.
- **`order.orphan_orders`도 시간대 기준이라** 다른 트래픽이 있으면 오탐할 수 있습니다.
- **`FAIL`이 나오면** 그 주문의 Outbox 행, `<토픽>.DLT` 레코드, 서비스 로그 순서로 확인합니다.

## Outbox 처리량·적체 확인

| 방법 | 확인 내용 |
|---|---|
| `outbox-watch.sh` | 서비스별 `pending`, `processing`, `sent`, `failed`, `consumer_lag` 추이를 CSV로 기록. `pending`이 계속 늘면 Publisher가 병목, `consumer_lag`가 늘면 Consumer가 병목입니다 |
| `sql/outbox-throughput.sql` | 각 서비스 DB에서 초당 발행량과 발행 지연(`sent_at - created_at`) 조회. 예: `psql -v since="'2026-01-01 00:00:00+09'" -f outbox-throughput.sql` |
| Prometheus | `order_outbox_events{status}`, `inventory_outbox_events{status}`, `order_outbox_publish_total`, `inventory_outbox_publish_total`. product-api에는 이 메트릭이 없어 위 SQL로 확인합니다 |

## 부하 테스트 본문과 VU 설정

요청 본문에는 현재 API가 요구하는 `shippingAddress`가 포함됩니다. 폴링으로 VU 점유 시간이 길어지므로 `maxVUs`는 `목표 RPS × (15초 + 여유 5초)`이고, `dropped_iterations`가 0보다 크면 threshold 실패입니다. E2E 지연에는 폴링 간격만큼(최대 약 1초)의 관측 지연이 포함됩니다.

