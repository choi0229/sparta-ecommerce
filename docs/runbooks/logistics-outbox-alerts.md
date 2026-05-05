# Runbook: logistics-api Outbox 알림 대응

이 문서는 `prometheus/alerts/logistics-outbox-alerts.yml`에 정의된 알림 각각에 대한 진단 절차를 설명합니다.

관련 ADR: [ADR-001: Outbox DLQ 도입 여부](../adr/001-outbox-dlq-decision.md)

---

## LogisticsOutboxFailedEventsPresent

**심각도**: warning  
**조건**: `logistics_outbox_events{status="FAILED"} >= 1` 이 5분 지속

**의미**

FAILED 상태 row가 존재합니다. publisher는 FAILED를 건너뛰므로 메인 파이프라인은 차단되지 않지만, 이벤트가 Consumer에 전달되지 않은 상태입니다.

**진단 절차**

1. FAILED 이벤트 목록 확인
   ```bash
   curl -s 'http://localhost:8084/admin/outbox?status=FAILED&limit=20' | jq .
   ```
2. `eventType`, `aggregateId`, `retryCount`, `payload` 확인
3. 원인 판별
   - `eventType`이 알 수 없는 값 → 코드 버그, 재처리해도 동일 실패
   - Kafka 브로커 일시 장애 → 복구 후 재처리 가능
   - Kafka topic 미존재 → topic 생성 후 재처리 가능
4. 원인 해결 후 재처리
   ```bash
   # 단건
   curl -s -X POST 'http://localhost:8084/admin/outbox/{id}/retry'
   # 배치 (기본 limit=20)
   curl -s -X POST 'http://localhost:8084/admin/outbox/retry?limit=20'
   ```

---

## LogisticsOutboxFailedEventsSurge

**심각도**: critical  
**조건**: `logistics_outbox_events{status="FAILED"} >= 5` 이 5분 지속

**의미**

FAILED가 5건 이상 누적되어 있습니다. Kafka 장애, topic 설정 오류, 또는 코드 배포 문제일 수 있습니다.

**진단 절차**

1. Kafka 브로커 상태 확인
   ```bash
   # docker-compose 환경
   docker-compose exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
   ```
2. logistics-api 로그에서 `[OutboxPublisher]`, `[OutboxTerminal]` 패턴 검색
   ```
   grep "\[OutboxTerminal\]" <log-file>
   ```
3. `LogisticsOutboxFailedEventsPresent` 절차와 동일하게 원인 분석 후 재처리
4. 재처리 후에도 FAILED가 재발하는 경우 → 코드/인프라 수준 문제로 에스컬레이션

---

## LogisticsOutboxPublishErrorsDetected

**심각도**: warning  
**조건**: `increase(logistics_outbox_publish_total{result="failed"}[5m]) >= 3` 이 2분 지속

**의미**

5분 동안 Kafka send 실패가 3회 이상 발생했습니다. 일시적 네트워크 문제이거나 Kafka 브로커 불안정 초기 신호일 수 있습니다.

**진단 절차**

1. Kafka 브로커 연결 상태 확인
2. logistics-api 로그에서 Kafka 관련 예외 검색
   ```
   grep "KafkaException\|TimeoutException\|ProducerFenced" <log-file>
   ```
3. 발행 실패 이벤트는 `retry_count`가 증가하며 최대 5회 후 FAILED로 전이됩니다.
4. 일시적 장애라면 retry backoff으로 자동 복구됩니다. FAILED로 전이 시 위 절차 참조.

---

## LogisticsOutboxPublishErrorsSurge

**심각도**: critical  
**조건**: `increase(logistics_outbox_publish_total{result="failed"}[5m]) >= 10` 이 2분 지속

**의미**

5분 동안 10회 이상 발행 실패입니다. Kafka 브로커 장애 또는 topic 비존재 가능성이 높습니다.

**진단 절차**

1. Kafka 브로커 상태 즉시 확인
   ```bash
   docker-compose exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092
   ```
2. topic 존재 여부 확인
   ```bash
   docker-compose exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
     --describe --topic shipment-events
   ```
3. Kafka 복구 불가 시 브로커 재시작 고려
4. 복구 후 FAILED 이벤트 배치 재처리

---

## LogisticsOutboxHighRetryEventsDetected

**심각도**: warning  
**조건**: `increase(logistics_outbox_stale_high_retry_total[10m]) > 0` 이 1분 지속

**의미**

`retry_count >= 3`인 PROCESSING 이벤트가 만료(stale) 후 recovery job에 의해 PENDING으로 복구되고 있습니다. 동일 이벤트가 반복 실패 중일 가능성이 있습니다.

**진단 절차**

1. FAILED 이벤트 중 `retryCount`가 높은 항목 확인
   ```bash
   curl -s 'http://localhost:8084/admin/outbox?status=FAILED&limit=50' | jq '.data | sort_by(-.retryCount)'
   ```
2. `[OutboxTerminal]` WARN 로그 검색 — terminal 전이 여부 확인
   ```
   grep "\[OutboxTerminal\]" <log-file>
   ```
3. 반복 실패 원인 분석 (`LogisticsOutboxPublishErrorsDetected` 절차 참조)
4. 원인이 코드 버그(알 수 없는 eventType 등)인 경우 코드 수정 후 재처리

---

## LogisticsOutboxStaleRecoveryFrequent

**심각도**: warning  
**조건**: `increase(logistics_outbox_stale_recovered_total[5m]) > 5` 이 10분 지속

**의미**

5분 동안 5건 초과의 stale 복구가 10분 이상 지속되고 있습니다. publisher가 claim 후 완료 처리 없이 자주 종료되고 있거나 JVM GC가 publisher를 과도하게 중단시키고 있을 수 있습니다.

**진단 절차**

1. JVM GC 로그 및 메모리 사용률 확인 (Grafana 대시보드)
2. publisher 스케줄러 로그에서 비정상 종료 패턴 검색
   ```
   grep "\[OutboxRecovery\]" <log-file>
   ```
3. logistics-api 재시작 이벤트 확인 (Kubernetes: `kubectl rollout history`)
4. stale recovery 임계값 (`next_retry_at = now + 2min`)이 publisher 처리 속도 대비 너무 짧은지 검토

---

## 지금 당장 적용할 것 / 나중에 적용할 것

### 지금 (이 runbook 작성 시점)

- [x] Prometheus alert rule 정의 (`prometheus/alerts/logistics-outbox-alerts.yml`)
- [x] Prometheus rule_files, scrape target 등록
- [x] docker-compose alerts 볼륨 마운트
- [x] 운영 대응 절차 문서화 (이 파일)

### 나중에 (Alertmanager 구성 시)

- [ ] Alertmanager 설치 및 `prometheus.yml`에 `alerting` 섹션 추가
- [ ] Slack webhook 연동 (`receivers`, `route` 구성)
- [ ] `severity=critical` 알림을 PagerDuty 또는 온콜 채널로 라우팅
- [ ] `ADR-001`의 DLQ 재검토 기준점(일평균 FAILED 100건)에 대한 알림 추가
