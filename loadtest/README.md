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