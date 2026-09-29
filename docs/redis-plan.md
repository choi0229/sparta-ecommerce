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
- [ ] avg/p95/p99, error rate, DB QPS, DB CPU, App CPU 기록
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
(실제 측정 후 채움)
