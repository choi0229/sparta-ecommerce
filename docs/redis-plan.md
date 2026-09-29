# dev-payment-cache: 주문 상태 폴링 캐시 + 장애 fallback

## 목표
주문·결제 상태 폴링이 매 요청 DB를 조회하는 구조를 재현하고, Cache-Aside로 읽기 부하를 줄인다.
DB를 Source of Truth로 유지하며 커밋 이후 캐시를 무효화해 정합성을 지키고,
Redis timeout/연결 실패 시 DB fallback으로 캐시 장애가 서비스 장애로 번지지 않게 한다.
k6와 Prometheus로 DB QPS, p95/p99, CPU, fallback 동작을 정량 검증한다.

## 배경 (현재 코드 사실)
- GET /api/orders/status/{idemKey}는 idempotency_request만 조회한다. 응답 status는 PENDING/COMPLETED/FAILED뿐이며, 주문 저장 시 한 번만 바뀐다.
- 실제 주문 진행 상태는 Orders.status(CREATED → RESERVED → PAID → COMPLETED, FAILED, EXPIRED)에 있지만 폴링 응답에 노출되지 않는다.
- Orders에는 idemKey 컬럼이 없다. idempotency_request.order_id에는 인덱스가 있다.
- 202 응답 직후에는 idempotency 레코드가 아직 없을 수 있다. 이때 서비스는 row 없이 PENDING을 반환한다.
- application.yml에 show-sql: true가 켜져 있다.

## 현재 구조 (Before)
Client polling → OrderController → OrderService.getOrderStatus
            → IdempotencyRepository.findById(idemKey) → PostgreSQL (매 요청)

## 설계 결정
- Payment 엔티티는 만들지 않는다. 폴링 응답에 Orders.status를 추가해 상태 전이를 노출한다.
- 캐시 키는 order:status:{idemKey}, 값은 OrderStatusResponse JSON. TTL은 5분 단일값이며 설정으로 분리한다.
- @Cacheable 대신 RedisTemplate 기반 OrderStatusCacheRepository를 쓰고, Redis 예외는 이 경계에서 처리한다.
- 조회 결과가 없는 경우(레코드 미존재 PENDING)는 캐시하지 않는다.
- 상태 변경 시 캐시를 갱신하지 않고 삭제한다. 삭제는 AFTER_COMMIT에서 수행한다.
- Redis 실패(timeout, 연결 실패)는 DB fallback으로 처리하고, 응답을 실패시키지 않는다.

## 체크리스트

### Phase 0. 준비
- [ ] dev-order에서 dev-payment-cache 브랜치 생성
- [ ] 현재 폴링 흐름(Before 아키텍처) 문서화

### Phase 1. 폴링 응답 확장 (DB-only)
- [ ] OrderStatusResponse에 orderStatus 추가
- [ ] 조회 로직을 OrderStatusQueryService로 분리 (idempotency 조회 → orderId 있으면 Orders 조회)
- commit: feat: expose order status in polling api

### Phase 2. DB-only 베이스라인
- [ ] 측정용 프로필에서 show-sql: false
- [ ] pg_stat_statements 활성화
- [ ] orders + idempotency_request seed 데이터 (약 1만 건)
- [ ] k6 order-status-poll.js (constant-arrival-rate 100 RPS, 5분, seed된 idemKey 풀에서 무작위 선택)
- [x] avg/p95/p99, error rate, DB QPS, DB CPU, App CPU 기록
- commit: perf: add DB-only polling benchmark

### Phase 3. Redis 환경
- [ ] docker-compose에 redis:7-alpine (container_name: ecommerce-redis, 6379)
- [ ] spring-boot-starter-data-redis 의존성 추가
- [ ] host/port/timeout 설정 (timeout 기본 100ms, 환경변수로 분리)
- commit: chore: add redis docker environment

### Phase 4. Cache-Aside
- [ ] OrderStatusCacheRepository (GET/SET/DEL)
- [ ] HIT → 반환 / MISS → DB → SET → 반환
- [ ] 조회 결과가 없으면 캐시하지 않음
- commit: feat: add order status cache-aside

### Phase 5. 커밋 이후 무효화
- [ ] 무효화 지점: IdempotencyService.complete() 1곳 + OrderSagaService.updateStatus() 6곳 (RESERVED, PAID, COMPLETED, FAILED×2, EXPIRED)
- [ ] OrderStatusChangedEvent(orderId) 발행
- [ ] @TransactionalEventListener(AFTER_COMMIT)에서 order_id로 idemKey 조회 후 DEL
- [ ] 통합 테스트: 상태 전이 직후 다음 폴링이 새 상태를 반환하는지 검증
- commit: feat: evict order status cache after commit

### Phase 6. Timeout + DB fallback
- [ ] QueryTimeoutException, RedisConnectionFailureException 등 DataAccessException 계층 처리
- [ ] SET 실패는 응답 성공으로 처리
- [ ] DEL 실패는 로그와 카운터만 기록 (TTL이 안전장치)
- commit: feat: add redis timeout and db fallback

### Phase 7. 메트릭
- [ ] order_status_cache_hit_total / miss_total / fallback_total / error_total
- [ ] Grafana: hit ratio, fallback 추이
- commit: feat: add cache metrics

### Phase 8. 측정 (베이스라인과 동일 조건)
- [ ] Cache Hit
- [ ] Cache Miss
- [ ] Redis stop (연결 실패 경로)
- [ ] Redis pause (100ms timeout 경로)
- [ ] 장애 복구 후 hit ratio 회복 확인
- commits: test: add redis failure scenarios / perf: compare DB-only and redis

### Phase 9. README
- [ ] 문제 → Before/After 아키텍처 → 결과 표 → 설계 결정 이유 (기존 검색 최적화 섹션과 같은 구조)

## 후속 과제 (측정 근거가 생길 때만)
- Cache stampede 측정 후 필요 시 TTL jitter / single-flight
- pause 실험에서 p95 증가가 크면 Resilience4j Circuit Breaker
- 결제 상태 모델이 필요해지면 Payment 도메인 분리

## 측정 결과 기록

### 측정 환경
- order-api는 perf 프로필(show-sql: false)로 로컬 실행
- order-api는 bootRun 기본 옵션 -XX:TieredStopAtLevel=1(C1 JIT만 사용)으로 실행
- order-db(postgres:16), zookeeper, kafka는 docker compose로 실행, pg_stat_statements 활성화
- seed 데이터: idempotency_request idem_key seed-000001 ~ seed-010000 (1만 건)과 대응 orders
- CPU 수집 (5초 간격)
  - DB: `docker stats --no-stream --format '{{.CPUPerc}}' <order-db 컨테이너>` (코어 1개 = 100%)
  - App: `/actuator/prometheus`의 `process_cpu_usage` × 100 (전체 코어 합 = 100%, system_cpu_count = 10)
  - 두 값은 기준이 달라 서로 직접 비교하지 않는다. 같은 지표끼리 RATE·조건 간 비교에만 쓴다.

### 측정 절차
```bash
bash loadtest/k6/measure-poll.sh <label> <run번호> [RATE]   # RATE 기본값 100
bash loadtest/k6/measure-poll.sh <label> idle               # k6 없이 5분간 CPU만 기록
# 예: bash loadtest/k6/measure-poll.sh baseline 1 300
```
- 결과 경로: loadtest/results/<label>/rate-<RATE>/run-<N>/{k6.log, db.log, cpu.log}, idle은 loadtest/results/<label>/idle/cpu.log
- 순서: 같은 RATE로 워밍업 1분(저장 안 함) → pg_stat_statements_reset → CPU 기록 시작 → 본 측정 5분 → CPU 기록 중지 → polling-query-stats.sql
- order-db 컨테이너는 `docker compose ps -q order-db`로 찾는다.
- 멈춤 조건: dropped_iterations 발생, http_req_failed > 0, checks < 100%

### DB-only 베이스라인 (2026-09-29)
idle 1회, RATE별 3회. 모든 회차에서 dropped_iterations 0, http_req_failed 0, checks 100%.
표의 값은 RATE별 3회 중앙값.

| RATE | avg (ms) | p95 (ms) | p99 (ms) | 실제 RPS | error rate | DB QPS | DB CPU 평균 | DB CPU (idle 차감) | App CPU 평균 |
|---|---|---|---|---|---|---|---|---|---|
| idle | - | - | - | - | - | - | 0.52% | - | 0.16% |
| 100 | 1.83 | 2.69 | 3.29 | 100.00 | 0.00% | 200.01 | 3.42% | 2.90% | 0.64% |
| 300 | 2.25 | 2.75 | 3.26 | 300.00 | 0.00% | 600.01 | 9.93% | 9.41% | 2.88% |
| 500 | 1.87 | 2.57 | 2.94 | 500.02 | 0.00% | 1000.01 | 13.46% | 12.94% | 3.97% |

- DB QPS = db.log의 idempotency_request 조회 + orders 조회 calls 합 ÷ 300 (요청 1건당 쿼리 2회)
- CPU 평균 = cpu.log에서 본 측정(idle은 기록) 시작~종료 시각 사이 샘플(회차당 48개)의 평균
- DB CPU (idle 차감) = 해당 RATE의 DB CPU 중앙값 − idle DB CPU(0.52%)

### 해석 시 주의
- 저부하(100 RPS)에서 응답시간이 고부하보다 느리거나 비슷하게 나온다. 추정 원인은 CPU 전력 관리(저부하 시 클럭·코어 절전)이며 검증하지 않았다.
- 따라서 응답시간은 같은 RATE끼리만 비교한다 (예: DB-only 300 RPS vs Redis 300 RPS).
- 100 RPS run-3은 avg 2.55ms / p95 3.30ms / p99 4.01ms로 run-1·2(avg 1.81~1.83ms)보다 높았다. 중앙값에는 run-2 값이 쓰였다.
- 같은 100 RPS에서 baseline-v1(avg 2.98ms)과 v2(avg 1.83ms)가 약 1ms 차이 났다. 측정 세션 간 편차가 약 1ms이므로 응답시간 차이가 이보다 작으면 개선으로 주장하지 않는다. DB QPS는 세션과 무관하게 일정하므로 주 비교 지표로 쓴다.
- Redis 측정은 앱 재시작 후 진행되므로, 측정 전 500 RPS로 3분간 사전 워밍업한다.

로그 파일:
- loadtest/results/baseline/idle/cpu.log
- loadtest/results/baseline/rate-{100,300,500}/run-{1,2,3}/{k6.log, db.log, cpu.log}

### baseline-v1 (보관)
loadtest/results/baseline-v1/은 측정 방법 변경(App CPU를 ps → actuator process_cpu_usage, idle 측정 추가) 전 결과다. 위 표와 비교하지 않는다.
