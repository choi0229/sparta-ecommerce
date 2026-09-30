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
- 캐시 키는 order:status:{idemKey}, 값은 OrderStatusResponse JSON. TTL은 상태별로 다르다(진행 중 5s / 완료 5m). 두 값은 설정으로 분리한다.
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
- [x] QueryTimeoutException, RedisConnectionFailureException 등 DataAccessException 계층 처리
- [x] SET 실패는 응답 성공으로 처리
- [x] DEL 실패는 로그와 카운터만 기록 (TTL이 안전장치)
- commit: feat: add redis timeout and db fallback

### Phase 7. 메트릭
- [x] order_status_cache_hit_total / miss_total / fallback_total / error_total
- [x] Grafana: hit ratio, fallback 추이
- commit: feat: add cache metrics

### Phase 8. 측정 (베이스라인과 동일 조건)
- [x] Cache Hit
- [ ] Cache Miss
- [x] Redis stop (연결 실패 경로)
- [x] Redis pause (100ms timeout 경로)
- [x] 장애 복구 후 hit ratio 회복 확인
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

### fallback 전 Redis 장애 측정 (2026-09-30)
- 측정 시점 커밋: 76e3c2b (Cache-Aside만 적용, Redis 예외 처리 없음), perf 프로필
- 조건: k6 constant-arrival-rate 100 RPS × 30초, 시나리오별 1회. normal만 30초 워밍업(캐시 채우기) 후 측정했고, stop/pause는 장애 주입 직후 워밍업 없이 측정했다. 참고용이며 베이스라인 표와 비교하지 않는다.

| 시나리오 | http_reqs | error rate | avg (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|---|
| normal | 3001 | 0.00% (0/3001) | 2.77 | 4.44 | 5.11 |
| stop | 3001 | 100.00% (3001/3001) | 0.43 | 0.69 | 1.35 |
| pause | 3001 | 100.00% (3001/3001) | 103.98 | 105.57 | 105.90 |

- stop: HTTP 500, `{"errorCode":"SERVER_ERROR","errorMessage":"Redis exception"}`, 응답 0.003s
- pause: HTTP 500, `{"errorCode":"SERVER_ERROR","errorMessage":"Redis command timed out"}`, 응답 0.106s
- 두 메시지 모두 GlobalExceptionHandler의 `Exception` catch-all이 `ex.getMessage()`를 그대로 내려준 값이다. 예외 클래스는 앱 로그로 확인하지 않았다.
- 복구 확인: unpause 후 healthy 확인 직후 seed-000001 조회 HTTP 200, `redis-cli GET order:status:seed-000001`로 캐시 값이 다시 채워진 것을 확인했다.

로그 파일:
- loadtest/results/no-fallback/{normal,stop,pause}/k6.log
- loadtest/results/no-fallback/{stop,pause}/sample-response.txt
- loadtest/results/no-fallback/recovery.txt

### Redis 적용 후 측정 (2026-10-01)
- 측정 시점 커밋: e29d211 (Cache-Aside + 커밋 이후 무효화 + 상태별 TTL + DB fallback + 캐시 메트릭), perf 프로필
- 실행: `loadtest/k6/run-phase8-a.sh` (앱 재시작 후 500 RPS 3분 사전 워밍업 → idle 1회 → RATE 100/300/500 각 3회 → 장애 시나리오), 진행 로그 loadtest/results/phase8-a.log
- 모든 회차 dropped_iterations 0, http_req_failed 0, checks 100%
- seed 1만 건의 주문 상태: COMPLETED 6000, PAID 2000, FAILED 1000, RESERVED 1000. TTL은 COMPLETED/FAILED 5m, PAID/RESERVED 5s
- Redis CPU는 docker stats 기준(코어 1개 = 100%). CPU 기록 루프에 redis 조회가 추가되어 회차당 샘플 수가 44개(baseline 48개)

#### 정상 비교 (RATE별 3회 중앙값)

| RATE | 구분 | DB QPS | DB CPU (idle 차감) | Redis CPU | App CPU | avg (ms) | p95 (ms) | p99 (ms) | hit ratio |
|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 200.01 | 2.90% | - | 0.64% | 1.83 | 2.69 | 3.29 | - |
| 100 | redis | 89.66 (−55.2%) | 1.26% (−56.6%) | 1.75% | 0.59% | 1.65 | 3.53 | 4.42 | 0.5517 |
| 300 | baseline | 600.01 | 9.41% | - | 2.88% | 2.25 | 2.75 | 3.26 | - |
| 300 | redis | 197.29 (−67.1%) | 2.41% (−74.4%) | 2.88% | 1.08% | 1.20 | 2.83 | 3.49 | 0.6712 |
| 500 | baseline | 1000.01 | 12.94% | - | 3.97% | 1.87 | 2.57 | 2.94 | - |
| 500 | redis | 284.85 (−71.5%) | 3.24% (−74.9%) | 3.91% | 1.40% | 1.03 | 2.64 | 3.09 | 0.7152 |

| idle | DB CPU | Redis CPU | App CPU |
|---|---|---|---|
| baseline | 0.52% | - | 0.16% |
| redis | 0.53% | 0.83% | 0.35% |

- DB QPS = db.log의 idempotency_request 조회 + orders 조회 calls 합 ÷ 300
- DB CPU (idle 차감) = DB CPU 중앙값 − 같은 label의 idle DB CPU (baseline 0.52%, redis 0.53%)
- 감소율 = (baseline − redis) ÷ baseline, 반올림 전 값으로 계산
- hit ratio = cache.log의 delta hit ÷ (hit + miss + error), 회차별 값의 중앙값
- CPU 평균 = cpu.log에서 meta.txt의 main_start~main_end 사이 샘플 평균 (idle은 idle_start~idle_end)

회차별 hit ratio: 100 RPS 0.5669 / 0.5517 / 0.5416, 300 RPS 0.6712 / 0.6693 / 0.6713, 500 RPS 0.7159 / 0.7146 / 0.7152

#### 장애 비교 (full 시나리오)

| 시나리오 | error rate | avg (ms) | p95 (ms) | p99 (ms) | DB QPS | cache error 수 |
|---|---|---|---|---|---|---|
| no-fallback stop | 100.00% (3001/3001) | 0.43 | 0.69 | 1.35 | - | - |
| no-fallback pause | 100.00% (3001/3001) | 103.98 | 105.57 | 105.90 | - | - |
| redis-stop-full | 0.00% (0/30001) | 1.58 | 2.23 | 2.61 | 199.98 | 29990 |
| redis-pause-full | 0.00% (0/30000) | 107.57 | 112.04 | 112.45 | 200.00 | 30000 |

- 조건이 다르다: no-fallback은 커밋 76e3c2b, 100 RPS × 30초 1회(워밍업 없음). full은 커밋 e29d211, 100 RPS × 5분 1회(1분 워밍업 후 본 측정 시작과 동시에 장애 주입, 5분 내내 장애)
- no-fallback 시점에는 db.log와 캐시 메트릭이 없어 DB QPS, cache error 수는 기록하지 않았다.
- cache error 수 = cache.log의 delta error. redis-stop-full의 delta는 hit 4, miss 7, error 29990이다.

#### 구간별 분석 (mid 시나리오, 100 RPS, 장애 60s 시점부터 120s)
구간은 meta.txt의 main_start / fault_start / fault_end / main_end로 나눴다. 값은 Prometheus(spring-boot job, 5s 스크랩)에서 각 구간 끝 시각에 `increase(...[구간 길이])`로 구했다.
- 평균 응답시간 = increase(http_server_requests_seconds_sum) ÷ increase(_count), uri="/api/orders/status/{idemKey}"
- 요청률 = increase(...) ÷ 구간 길이(초)
- pending 최댓값 = max_over_time(hikaricp_connections_pending[구간 길이])

redis-pause-mid (fault_start 01:12:18, fault_end 01:14:18, redis_healthy 01:14:29)

| 구간 | 시각 | 평균 응답시간 (ms) | status별 req/s | hit / miss / error req/s | pending 최댓값 |
|---|---|---|---|---|---|
| 장애 전 | 01:11:18~01:12:18 (60s) | 1.61 | 200: 99.95 | 42.01 / 57.93 / 0.00 | 0 |
| 장애 중 | 01:12:18~01:14:18 (120s) | 107.43 | 200: 100.00 | 0.00 / 0.00 / 100.00 | 0 |
| 복구 후 | 01:14:18~01:16:19 (121s) | 1.45 | 200: 100.00 | 52.71 / 47.30 / 0.00 | 0 |

redis-stop-mid (fault_start 01:18:20, fault_end 01:20:20, redis_healthy 01:20:30)

| 구간 | 시각 | 평균 응답시간 (ms) | status별 req/s | hit / miss / error req/s | pending 최댓값 |
|---|---|---|---|---|---|
| 장애 전 | 01:17:19~01:18:20 (61s) | 1.49 | 200: 99.47 | 48.52 / 50.95 / 0.00 | 0 |
| 장애 중 | 01:18:20~01:20:20 (120s) | 1.45 | 200: 100.00 | 0.00 / 0.00 / 100.00 | 0 |
| 복구 후 | 01:20:20~01:22:20 (120s) | 1.78 | 200: 100.00 | 29.94 / 70.05 / 0.00 | 0 |

- 두 시나리오 모두 200 외 status는 없었다. hikaricp_connections_active 최댓값은 모든 구간에서 1.
- 복구 후 cache error가 0으로 돌아오기까지: 두 시나리오 모두 fault_end 이후 첫 스크랩 구간에서만 error가 증가했고(pause 01:14:18→01:14:23 +440, stop 01:20:20→01:20:25 +471) 그 뒤로는 증가가 없었다. 스크랩 간격이 5s이므로 0~5s 안이다.
- mid 시나리오의 k6 요약(구간 혼합): pause avg 44.06 / p95 111.74 / p99 112.15 ms, stop avg 1.77 / p95 3.04 / p99 3.89 ms, 모두 http_req_failed 0

로그 파일:
- loadtest/results/phase8-a.log
- loadtest/results/redis/idle/{cpu.log, meta.txt}
- loadtest/results/redis/rate-{100,300,500}/run-{1,2,3}/{k6.log, db.log, cpu.log, cache.log, meta.txt}
- loadtest/results/redis-{pause,stop}-{full,mid}/rate-100/run-1/{k6.log, db.log, cpu.log, cache.log, meta.txt}
