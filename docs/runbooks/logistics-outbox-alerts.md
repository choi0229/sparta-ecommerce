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

### 완료

- [x] Prometheus alert rule 정의 (`prometheus/alerts/logistics-outbox-alerts.yml`)
- [x] Prometheus rule_files, scrape target 등록
- [x] docker-compose alerts 볼륨 마운트
- [x] 운영 대응 절차 문서화 (이 파일)
- [x] `deployment/infra/alertmanager.yaml` 추가 (ConfigMap, Deployment, Service)
- [x] `deployment/infra/prometheus.yaml`에 `alerting:` 섹션 추가
- [x] `prometheus/prometheus.yml`에 `alerting:` 섹션 추가 (로컬 docker-compose용)
- [x] Alertmanager 라우팅 검증 — `warning` → `slack-warning`, `critical` → `slack-critical`
- [x] inhibit_rules 검증 — critical 발화 시 동일 alertname+service의 warning이 `suppressed`
- [x] Slack send 시도 로그 확인 — placeholder URL로 인한 HTTP 오류, 라우팅 자체는 정상

### 남은 작업

- [ ] 실제 Slack Webhook URL 교체 및 Slack 채널 수신 확인 (아래 절차 참조)
- [ ] `severity=critical` 알림을 PagerDuty 또는 온콜 채널로 라우팅 고도화
- [ ] `ADR-001`의 DLQ 재검토 기준점(일평균 FAILED 100건)에 대한 알림 추가

---

## Alertmanager 운영 적용 절차

> 현재 클러스터 상태 (검증 완료):
> - Alertmanager: `Running` (monitoring namespace)
> - Prometheus → Alertmanager 연결: `activeAlertmanagers` 1개 확인
> - 6개 alert rule: 모두 `inactive` (logistics-api 정상 동작 중)
> - 라우팅: `warning` → `slack-warning`, `critical` → `slack-critical` 정상
> - inhibit_rules: critical 발화 시 동일 (alertname, service)의 warning이 `suppressed` 정상
> - **미완료**: 실제 Slack Webhook URL 미설정 → Slack 수신 미검증

### 1. Alertmanager / Prometheus 배포 (최초 또는 재적용)

```bash
kubectl apply -f deployment/infra/alertmanager.yaml
kubectl apply -f deployment/infra/prometheus.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/alertmanager -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

### 2. Slack Webhook URL 활성화

Alertmanager ConfigMap에는 현재 placeholder URL이 설정되어 있습니다.  
실제 Slack 알림을 받으려면 아래 두 방법 중 하나를 선택합니다.

**방법 A — ConfigMap 직접 수정 (내부 환경용)**

```bash
kubectl edit configmap alertmanager-config -n monitoring
# slack_api_url: 'https://hooks.slack.com/services/REPLACE/WITH/REAL_WEBHOOK'
# 위 값을 실제 Webhook URL로 교체

kubectl rollout restart deployment/alertmanager -n monitoring
```

**방법 B — Secret으로 config 파일 통째 관리 (운영 환경 권장)**

```bash
# 1. 실제 URL이 포함된 alertmanager.yml 로컬 파일 준비
# 2. Secret 생성
kubectl create secret generic alertmanager-config \
  --from-file=alertmanager.yml=./alertmanager.yml \
  -n monitoring

# 3. deployment/infra/alertmanager.yaml 의 volume 수정:
#    volumes.configMap.name: alertmanager-config  →  volumes.secret.secretName: alertmanager-config
kubectl apply -f deployment/infra/alertmanager.yaml
kubectl rollout restart deployment/alertmanager -n monitoring
```

### 3. 적용 검증

```bash
# Pod 상태
kubectl get pods -n monitoring

# Alertmanager readiness / healthy
kubectl port-forward svc/alertmanager 9093:9093 -n monitoring &
curl -s http://localhost:9093/-/ready    # 기대: OK
curl -s http://localhost:9093/-/healthy  # 기대: OK

# Prometheus → Alertmanager 연결 확인
kubectl port-forward svc/prometheus 9090:9090 -n monitoring &
curl -s http://localhost:9090/api/v1/alertmanagers | python3 -m json.tool
# 기대: activeAlertmanagers 에 alertmanager.monitoring.svc.cluster.local:9093 존재

# alert rules 인식 확인
curl -s "http://localhost:9090/api/v1/rules?type=alert" | python3 -m json.tool
# 기대: logistics-outbox group 내 6개 rule

# 포트포워드 정리
kill %1 %2 2>/dev/null || true
```

### 4. 알림 채널 및 inhibit_rules

- `#alerts-warning`: `severity=warning` 알림 수신 채널
- `#alerts-critical`: `severity=critical` 알림 수신 채널
- `send_resolved: true` — 알림 해소 시 Slack에 resolved 메시지가 전송됩니다.

같은 `(alertname, service)` 조합에서 `critical`이 발화하면 `warning`은 억제됩니다.  
`LogisticsOutboxFailedEventsSurge`(critical) 발화 시 `LogisticsOutboxFailedEventsPresent`(warning)은 Slack으로 전송되지 않습니다.

---

## Slack 실제 수신 검증 절차

Webhook URL 교체 후 아래 절차로 end-to-end 수신을 검증합니다.

### 사전 준비: Slack Incoming Webhook 생성

1. [Slack API](https://api.slack.com/apps) → 앱 선택 또는 신규 생성
2. **Incoming Webhooks** → Activate Incoming Webhooks: On
3. **Add New Webhook to Workspace** → `#alerts-warning` 채널 선택 → URL 복사
4. (선택) `#alerts-critical` 채널용 Webhook URL 별도 생성

### 1단계: Webhook URL 교체 (git에 절대 커밋하지 말 것)

```bash
# 터미널에서 직접 실행 — 이 채팅창에 URL 입력 금지
kubectl edit configmap alertmanager-config -n monitoring
# slack_api_url 값을 실제 URL로 교체하고 저장

kubectl rollout restart deployment/alertmanager -n monitoring
kubectl rollout status deployment/alertmanager -n monitoring
```

### 2단계: test alert 발화 (Alertmanager API 직접 POST)

```bash
kubectl port-forward svc/alertmanager 9093:9093 -n monitoring &

NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
ENDS=$(date -u -v+10M +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -d "+10 minutes" +"%Y-%m-%dT%H:%M:%SZ")

# warning alert 발화 → #alerts-warning 채널 수신 확인
curl -XPOST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d "[{
    \"labels\": {\"alertname\": \"TestSlackAlert\", \"severity\": \"warning\", \"service\": \"logistics-api\"},
    \"annotations\": {\"summary\": \"Slack 수신 테스트\", \"description\": \"warning receiver 검증\"},
    \"startsAt\": \"${NOW}\", \"endsAt\": \"${ENDS}\"
  }]"
```

Slack `#alerts-warning` 채널에서 확인할 것:
- `[WARNING] TestSlackAlert` 메시지 수신
- `description` 필드에 "warning receiver 검증" 텍스트 표시
- 약 30초 내 수신 (`group_wait: 30s`)

### 3단계: resolved 메시지 확인

```bash
PAST=$(date -u -v-1M +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -d "-1 minutes" +"%Y-%m-%dT%H:%M:%SZ")
START=$(date -u -v-15M +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -d "-15 minutes" +"%Y-%m-%dT%H:%M:%SZ")

curl -XPOST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d "[{
    \"labels\": {\"alertname\": \"TestSlackAlert\", \"severity\": \"warning\", \"service\": \"logistics-api\"},
    \"annotations\": {\"summary\": \"Slack 수신 테스트\", \"description\": \"warning receiver 검증\"},
    \"startsAt\": \"${START}\", \"endsAt\": \"${PAST}\"
  }]"
```

Slack `#alerts-warning` 채널에서 확인할 것:
- `[RESOLVED] TestSlackAlert` 또는 resolved 표시 메시지 수신

### 4단계: inhibit_rules 검증

```bash
# critical 발화
curl -XPOST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d "[{
    \"labels\": {\"alertname\": \"TestSlackAlert\", \"severity\": \"critical\", \"service\": \"logistics-api\"},
    \"annotations\": {\"summary\": \"critical 테스트\", \"description\": \"critical receiver 검증\"},
    \"startsAt\": \"${NOW}\", \"endsAt\": \"${ENDS}\"
  }]"

# 확인: warning이 suppressed 상태인지
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool | \
  python3 -c "
import sys, json
for a in json.load(sys.stdin):
    print(a['labels']['severity'], a['status']['state'], a['status'].get('inhibitedBy', []))
"
# 기대: warning suppressed [critical-alert-id], critical active []
```

Slack `#alerts-critical` 채널에 critical 메시지만 수신 (warning 억제 확인).

### 5단계: 정리

```bash
kill %1 2>/dev/null || true  # 포트포워드 종료
```
