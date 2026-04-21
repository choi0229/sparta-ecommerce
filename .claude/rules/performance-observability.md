---
paths:
  - "loadtest/**"
  - "prometheus/**"
  - "grafana/**"
  - "logstash/**"
  - "deployment/**"
---

# 성능 및 관측성 작업 규칙

이 문서는 성능 개선, 부하 테스트, 모니터링, 로깅, 운영 안정성과 관련된 공통 규칙을 정의합니다.

## 핵심 원칙

이 프로젝트는 측정 가능한 성능 개선과 운영 안정성을 중요하게 다룹니다.

성능 개선을 주장할 때는 측정 결과, 로그, 테스트 결과, 실행 계획, 또는 명확한 근거를 제시해야 합니다.

검증되지 않은 성능 개선을 단정하지 않습니다.

---

## 성능 변경 시 검토 항목

성능에 민감한 코드를 변경할 때는 다음 항목에 대한 영향을 검토합니다.

- p95 latency
- p99 latency
- 실패율
- 처리량
- DB connection usage
- DB query execution time
- Kafka consumer lag
- Elasticsearch query latency
- JVM memory usage
- GC 영향
- thread blocking
- transaction duration
- timeout
- retry 증가 여부

---

## 주문 흐름 성능 규칙

주문 흐름을 변경할 때는 다음을 특히 주의합니다.

- DB 커넥션 풀 고갈 가능성
- `@Transactional` 내부 blocking 호출 여부
- Kafka request-reply 대기 여부
- 비동기 처리 안정성
- Outbox publisher 지연
- Consumer lag
- 중복 이벤트 처리 비용
- 상태 조회 API 부하

주문 생성 과정에서 DB 커넥션을 오래 점유하는 구조를 다시 도입하지 않습니다.

---

## 검색 성능 규칙

상품 검색 로직을 변경할 때는 다음을 검토합니다.

- PostgreSQL full scan 발생 여부
- `LIKE %keyword%` 재도입 여부
- Elasticsearch query 성능
- index mapping 적절성
- sort field 성능
- pagination 성능
- DB fallback 비용
- 검색 결과 응답 크기

검색 성능 개선은 가능한 경우 k6 또는 쿼리 실행 결과로 검증합니다.

---

## k6 부하 테스트 규칙

부하 테스트 스크립트를 수정하거나 성능 개선을 검증할 때는 다음을 명확히 기록합니다.

- 테스트 대상 API
- 시나리오
- 요청 비율
- duration
- VUs 또는 arrival rate
- 성공률
- 실패율
- p95 latency
- p99 latency
- 주요 병목 지점

성능 수치를 비교할 때는 before/after 조건이 동일한지 확인합니다.

서로 다른 환경에서 측정한 결과를 직접 비교하지 않습니다.

---

## Prometheus / Grafana 규칙

모니터링 설정을 변경할 때는 다음을 고려합니다.

- HTTP request latency
- error rate
- JVM memory
- GC
- thread count
- DB connection pool
- Kafka consumer lag
- Elasticsearch latency
- service availability

Grafana dashboard를 수정할 때는 운영자가 병목을 빠르게 파악할 수 있도록 지표명을 명확히 유지합니다.

---

## ELK / Logstash 규칙

로그 수집 또는 분석 설정을 변경할 때는 다음을 고려합니다.

- 서비스명
- trace 식별자
- orderId
- eventId
- sagaId
- idempotencyKey
- error message
- timestamp
- log level

로그는 장애 원인 분석에 도움이 되어야 합니다.

민감 정보는 로그에 남기지 않습니다.

---

## 로깅 규칙

Saga, Outbox, Kafka, 결제, 재고 로직을 수정할 때는 필요한 경우 다음 식별자를 로그에 포함합니다.

- orderId
- productId
- skuId
- reservationId
- paymentId
- eventId
- sagaId
- idempotencyKey
- outboxId

다음 정보는 로그에 남기지 않습니다.

- password
- token
- secret
- access key
- private key
- 개인 인증 정보
- 운영 환경 민감 값

---

## 인프라 변경 규칙

`deployment/`, `prometheus/`, `grafana/`, `logstash/` 하위 파일을 수정할 때는 변경 목적을 명확히 합니다.

Kubernetes manifest 수정 시 다음을 고려합니다.

- replica 수
- resource requests/limits
- environment variables
- service name
- port
- readiness probe
- liveness probe
- namespace
- config map
- secret 참조 여부

명시적인 지시 없이 리소스 삭제 명령을 실행하지 않습니다.

---

## 금지되는 성능 관련 행동

다음 행동은 금지합니다.

- 측정 없이 성능이 개선되었다고 주장하는 것
- 테스트 조건이 다른 before/after 수치를 직접 비교하는 것
- 장애를 숨기기 위해 timeout만 늘리는 것
- 근본 원인 분석 없이 retry만 추가하는 것
- DB full scan 문제를 캐시로만 덮는 것
- 로그에서 민감 정보를 출력하는 것
- 운영 설정을 임의로 단순화하는 것

---

## 성능 변경 응답 형식

성능 관련 작업을 완료할 때는 다음 순서로 응답합니다.

1. 변경한 내용
2. 병목 또는 문제 원인
3. 기대 효과
4. 검증 방법
5. 실제 측정 결과
6. 남은 리스크

실제 측정을 하지 못했다면, 측정하지 못한 이유와 사용자가 실행해야 할 명령어를 명확히 설명합니다.