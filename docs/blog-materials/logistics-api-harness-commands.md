# logistics-api + Claude Code 하네스 주요 명령어

실제 실행한 명령어를 작업 단계 순서대로 정리했습니다.

---

## 개발 환경 준비

```bash
# Xcode Command Line Tools 설치 (git 등 기본 도구)
xcode-select --install

# Homebrew 설치
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Java 17 설치
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version
# openjdk version "17.x.x" 확인
```

---

## logistics-api 테스트 및 빌드

```bash
cd logistics-api

# 단위 테스트 실행 (외부 의존성 없음)
./gradlew test

# bootJar 빌드 (테스트 제외)
./gradlew bootJar -x test

# 빌드 결과물 확인
ls build/libs/
# logistics-api-0.0.1-SNAPSHOT.jar
```

---

## Guardrails 로컬 실행

```bash
# staged 파일 대상으로 검사
git add <파일>
bash scripts/claude-guardrails.sh
# Claude guardrails passed.

# CI 모드 시뮬레이션 (마지막 커밋 기준 diff)
CI=true bash scripts/claude-guardrails.sh
```

---

## Minikube 및 클러스터 준비

```bash
# Minikube 상태 확인
minikube status

# Minikube 시작 (Docker Desktop 드라이버)
minikube start

# kubectl 컨텍스트 확인
kubectl config current-context
# minikube 이어야 함

# ecommerce namespace 확인
kubectl get namespace ecommerce

# namespace 없을 경우 생성
kubectl apply -f deployment/namespace.yaml
```

---

## 인프라 의존성 적용

```bash
# ConfigMap, Secret 적용
kubectl apply -f deployment/ecommerce-config.yaml
kubectl apply -f deployment/db-secret.yaml

# 적용 확인
kubectl get configmap ecommerce-config -n ecommerce
kubectl get secret db-secret -n ecommerce

# Kafka Pod 확인
kubectl get pod -n ecommerce -l app=kafka

# logistics-db StatefulSet 적용
kubectl apply -f deployment/infra/db/logistics-db.yaml

# logistics-db Ready 대기
kubectl rollout status statefulset/logistics-db -n ecommerce
kubectl get pod -n ecommerce -l app=logistics-db
```

---

## logistics-api 매니페스트 적용

```bash
kubectl apply -f deployment/logistics-api/service.yaml
kubectl apply -f deployment/logistics-api/deployment.yaml

# 적용 확인
kubectl get svc logistics-api-svc -n ecommerce
kubectl get deployment logistics-api -n ecommerce
```

---

## logistics-api 이미지 빌드 및 배포 (redeploy-api.sh)

```bash
# logistics-api 전체 빌드 + Minikube 이미지 로드 + 배포
./scripts/redeploy-api.sh logistics-api

# 스크립트 내부 실행 순서:
# [1] cd logistics-api
# [2] kubectl scale deployment logistics-api --replicas=0 -n ecommerce
# [3] minikube image rm sparta-msa-final-project-logistics-api:latest
# [4] minikube image ls | grep logistics
# [5] ./gradlew bootJar -x test
# [6] docker build -t sparta-msa-final-project-logistics-api:latest .
# [7] minikube image load sparta-msa-final-project-logistics-api:latest
# [8] kubectl scale deployment logistics-api --replicas=2 -n ecommerce
#     kubectl rollout status deployment logistics-api -n ecommerce
```

---

## 배포 상태 확인

```bash
# Pod 상태 확인
kubectl get pod -n ecommerce -l app=logistics-api
# NAME                            READY   STATUS    RESTARTS   AGE
# logistics-api-xxxxxxxxx-xxxxx   2/2     Running   0          ...
# logistics-api-xxxxxxxxx-xxxxx   2/2     Running   0          ...

# Pod 로그 확인
kubectl logs -n ecommerce -l app=logistics-api --tail=50
# "Started LogisticsApiApplication" 확인
# Flyway migration 완료 확인
# Kafka consumer 연결 확인

# Service, Endpoint 확인
kubectl get svc logistics-api-svc -n ecommerce
kubectl get endpoints logistics-api-svc -n ecommerce

# 이벤트 확인 (문제 발생 시)
kubectl describe pod -n ecommerce -l app=logistics-api
```

---

## port-forward 및 API 검증

```bash
# port-forward 실행 (별도 터미널)
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce

# Health 확인
curl -s http://localhost:8084/actuator/health | jq .
# {"status":"UP", "components":{"db":{"status":"UP"}, ...}}

# 배송 생성 (POST /shipments)
curl -s -X POST http://localhost:8084/shipments \
  -H "Content-Type: application/json" \
  -d '{"orderId": 1, "recipientName": "홍길동", "recipientAddress": "서울시 강남구"}' | jq .
# HTTP 201 Created
# {"id": 1, "orderId": 1, "status": "READY", ...}

# 배송 조회 (GET /shipments/1)
curl -s http://localhost:8084/shipments/1 | jq .
# HTTP 200 OK

# 상태 전이: READY → SHIPPED
curl -s -X PATCH http://localhost:8084/shipments/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "SHIPPED"}' | jq .
# HTTP 200 OK, "status": "SHIPPED"

# 상태 전이: SHIPPED → IN_TRANSIT
curl -s -X PATCH http://localhost:8084/shipments/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "IN_TRANSIT"}' | jq .
# HTTP 200 OK, "status": "IN_TRANSIT"

# 상태 전이: IN_TRANSIT → DELIVERED
curl -s -X PATCH http://localhost:8084/shipments/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "DELIVERED"}' | jq .
# HTTP 200 OK, "status": "DELIVERED"

# 잘못된 전이: DELIVERED → FAILED (차단 확인)
curl -s -X PATCH http://localhost:8084/shipments/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "FAILED"}' | jq .
# HTTP 400 Bad Request
# {"errorCode": "INVALID_SHIPMENT_STATUS_TRANSITION", ...}

# FAILED 아웃박스 이벤트 목록 조회 (최대 200건, 기본 20건)
curl -s "http://localhost:8084/admin/outbox?status=FAILED&limit=20" | jq .
# HTTP 200 OK — {"data": [...], "message": "OK"}

# 단건 FAILED 이벤트 수동 재처리 (id=1 → PENDING 전환)
curl -s -X POST http://localhost:8084/admin/outbox/1/retry | jq .
# HTTP 200 OK — {"data": {"id": 1, "status": "PENDING", "retryCount": ...}}

# FAILED 이벤트 배치 재처리 (한 트랜잭션, 최대 20건)
curl -s -X POST "http://localhost:8084/admin/outbox/retry?status=FAILED&limit=20" | jq .
# HTTP 200 OK — {"data": {"count": N, "ids": [...]}}

# Prometheus 메트릭 확인 (Outbox status별 row count, 발행 성공/실패 등)
curl -s http://localhost:8084/actuator/prometheus | grep logistics_outbox
```

---

## GitHub Actions CI Gate 트리거 확인

```bash
# 1. 변경 파일 확인 (범위 파악 후 add)
git status --short
# M  logistics-api/src/main/...
# M  scripts/claude-guardrails.sh
# ?? .claude/agents/

# 2. 실제 변경 파일만 개별 add
git add logistics-api/src/main/java/.../OutboxQueryRepository.java
git add logistics-api/src/main/resources/application.yml
git add scripts/claude-guardrails.sh
git add .claude/agents/

# git add . 또는 git add -A 는 .env, 빌드 산출물, IDE 설정 파일이
# 의도치 않게 포함될 수 있으므로 피합니다.

# 3. guardrails 로컬 사전 검사
bash scripts/claude-guardrails.sh
# Claude guardrails passed.

# 4. 커밋 및 푸시
git commit -m "feat: add logistics-api"
git push origin dev-logitical

# GitHub Actions에서 다음 job 순서로 실행됨:
# 1. Claude Guardrails
# 2. logistics-api Tests
# 3. logistics-api Build
```

---

## 하네스 고도화 — 테스트 보강 및 커밋 흐름

테스트 추가 후 CI Gate 통과까지의 전체 흐름입니다.

```bash
# 1. 단위 테스트 실행 (외부 의존성 없음)
cd logistics-api
./gradlew test

# 기대 출력:
# OutboxEventTest > markSent_changeStatusToSent() PASSED
# OutboxEventTest > markFailed_belowMaxRetry_remainsPendingWithBackoff() PASSED
# OutboxEventTest > markFailed_atMaxRetry_becomeFailed() PASSED
# OutboxEventTest > markFailed_backoffIncreasesByRetry() PASSED
# ShipmentStatusTransitionTest > ready_to_shipped() PASSED
# ShipmentStatusTransitionTest > ready_to_canceled() PASSED
# ShipmentStatusTransitionTest > shipped_allowed_transitions() PASSED
# ShipmentStatusTransitionTest > in_transit_allowed_transitions() PASSED
# ShipmentStatusTransitionTest > delivered_cannot_transition() PASSED
# ShipmentStatusTransitionTest > terminal_states_cannot_transition() PASSED
# ShipmentStatusTransitionTest > ready_to_in_transit_throws() PASSED
# OutboxEventTransactionalServiceTest > markSent_fetchesEventAndSaves() PASSED
# OutboxEventTransactionalServiceTest > markFailed_fetchesEventAndSchedulesRetry() PASSED
# OutboxEventTransactionalServiceTest > markSent_notFound_throwsDomainException() PASSED
# OutboxEventTransactionalServiceTest > markFailed_notFound_throwsDomainException() PASSED
# OutboxEventTransactionalServiceTest > retryFailed_failedEvent_resetsToPending() PASSED
# OutboxEventTransactionalServiceTest > retryFailed_notFound_throwsDomainException() PASSED
# OutboxEventTransactionalServiceTest > retryFailed_notFailedStatus_throwsDomainException() PASSED
# OutboxEventTransactionalServiceTest > findByStatus_returnsMatchingEvents() PASSED
# OutboxEventTransactionalServiceTest > findByStatus_limitsToMax() PASSED
# OutboxEventTransactionalServiceTest > retryFailedBatch_resetsAllToPending() PASSED
# OutboxEventTransactionalServiceTest > retryFailedBatch_notFailedStatus_throwsDomainException() PASSED
# OutboxEventTransactionalServiceTest > retryFailedBatch_noEvents_returnsEmpty() PASSED
# OrderEventConsumerTest > validJson_callsCreateShipmentWithCorrectIdemKeyAndNullAddress() PASSED
# OrderEventConsumerTest > validJson_nullReturnFromService_completesNormally() PASSED
# OrderEventConsumerTest > invalidJson_catchesJsonProcessingException_doesNotRethrow() PASSED
# OrderEventConsumerTest > serviceThrowsRuntimeException_wrapsAsDomainException() PASSED
# OutboxPublisherJobTest > emptyBatch_noInteractions() PASSED
# OutboxPublisherJobTest > kafkaSendSuccess_callsMarkSent() PASSED
# OutboxPublisherJobTest > kafkaSendFails_callsMarkFailed() PASSED
# OutboxPublisherJobTest > unknownEventType_callsMarkFailedWithoutKafkaSend() PASSED
# StaleOutboxRecoveryJobTest > noStaleEvents_callsRecoverOnce() PASSED
# StaleOutboxRecoveryJobTest > staleEventsExist_callsRecoverOnce() PASSED
# BUILD SUCCESSFUL

# 2. bootJar 빌드 (테스트 제외)
./gradlew bootJar -x test
# BUILD SUCCESSFUL
# build/libs/logistics-api-0.0.1-SNAPSHOT.jar 생성 확인

# 3. guardrails 로컬 검사 (커밋 전)
cd ..

# 변경 파일 확인 후 실제 변경된 파일만 add
git status --short
# M  logistics-api/src/main/...
# M  logistics-api/src/test/...
# ?? .claude/agents/msa-architect.md
# 등 확인

# 확인한 파일만 개별 add (git add . 는 빌드 산출물·IDE 설정 포함 위험)
git add logistics-api/src/test/java/org/teamsparta/logisticsapi/domain/logistics/OutboxEventTest.java
git add logistics-api/src/test/java/org/teamsparta/logisticsapi/domain/logistics/OutboxEventTransactionalServiceTest.java
git add logistics-api/src/test/java/org/teamsparta/logisticsapi/domain/logistics/OrderEventConsumerTest.java
git add logistics-api/src/test/java/org/teamsparta/logisticsapi/domain/logistics/OutboxPublisherJobTest.java
git add logistics-api/src/main/java/org/teamsparta/logisticsapi/domain/logistics/repository/OutboxQueryRepository.java
git add logistics-api/src/main/resources/application.yml
git add .claude/agents/
git add scripts/claude-guardrails.sh

bash scripts/claude-guardrails.sh
# Claude guardrails passed.

# 4. 커밋 및 푸시
git commit -m "feat: add agent harness and improve logistics-api tests"
git push origin dev-logitical

# GitHub Actions CI Gate 자동 트리거:
# 1. Claude Guardrails
# 2. logistics-api Tests
# 3. logistics-api Build
```

---

## 유용한 디버깅 명령어

```bash
# 모든 ecommerce Pod 상태 한 번에 확인
kubectl get pod -n ecommerce

# 특정 Pod 상세 이벤트 (이미지 pull 실패, OOM 등 확인)
kubectl describe pod <pod-name> -n ecommerce

# 이전 컨테이너 로그 (CrashLoopBackOff 원인 파악)
kubectl logs -n ecommerce <pod-name> --previous

# Minikube 내부 이미지 목록
minikube image ls | grep logistics

# StatefulSet 롤아웃 상태 확인
kubectl rollout status statefulset/logistics-db -n ecommerce

# Deployment 롤아웃 상태 확인
kubectl rollout status deployment/logistics-api -n ecommerce

# Flyway migration 로그 필터
kubectl logs -n ecommerce -l app=logistics-api | grep -i flyway
```

---

## Cross-service E2E Smoke Test

order-api → Kafka → logistics-api 연결을 로컬 환경에서 검증하는 최소 happy path 스크립트.  
1차 (shipmentStatus=READY) + 2차 (배송 상태 변경 후 shipmentStatus=SHIPPED) 를 순서대로 검증한다.

**사전 조건**
- `kubectl port-forward svc/order-api 8083:8083 -n ecommerce` 가 실행 중이어야 한다.
- `kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce` 가 실행 중이어야 한다.
- logistics-api 와 Kafka 가 정상 기동 상태여야 한다.

```bash
# 실행
bash scripts/e2e-order-shipment-smoke.sh

# 기대 출력 (성공 시)
# === [1/5] POST /api/orders — 주문 생성 ===
# [OK] idemKey = xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
# === [2/5] ... — 1차 목표: shipmentStatus=READY — 폴링 시작 (최대 30초) ===
# [0s]  status=null       shipmentStatus=null
# [3s]  status=CREATED    shipmentStatus=null
# [6s]  status=CREATED    shipmentStatus=READY
# [OK] 1차 조건 달성: status=CREATED  shipmentStatus=READY
# === [3/5] orderId 추출 및 shipmentId 조회 ===
# [OK] orderId = 42
# [OK] shipmentId = 7
# === [4/5] PATCH /shipments/7/status — READY → SHIPPED ===
# [OK] logistics-api shipment status = SHIPPED
# === [5/5] ... — 2차 목표: shipmentStatus=SHIPPED — 폴링 시작 (최대 30초) ===
# [0s]  status=CREATED    shipmentStatus=READY
# [3s]  status=CREATED    shipmentStatus=SHIPPED
# === Smoke test 결과 ===
# [PASS] 1차: status=CREATED  shipmentStatus=READY
# [PASS] 2차: status=CREATED  shipmentStatus=SHIPPED
```

성공 조건: 1차 `shipmentStatus=READY` → 2차 `shipmentStatus=SHIPPED`  
종료 코드: 성공 `0` / 실패(타임아웃 포함) `1`

---

## Negative Smoke Test — invalid SKU 주문 실패 경로 검증

order-api → product-api(MISSING_SKU 실패 reply) → order-api 상태 FAILED 반영을 검증한다.

**사전 조건**
- `kubectl port-forward svc/order-api 8083:8083 -n ecommerce` 가 실행 중이어야 한다.
- product-api 가 Kafka로 연결되어 `productSnapshot-requested-event` 를 수신할 수 있어야 한다.

```bash
# 실행
bash scripts/e2e-order-invalid-sku-smoke.sh

# 기대 출력 (성공 시)
# === [1/2] POST /api/orders — invalid SKU로 주문 생성 ===
# [OK] idemKey = xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
# === [2/2] GET /api/orders/status/... — 목표: status=FAILED — 폴링 시작 (최대 30초) ===
# [0s]  status=PENDING  orderId=null  shipmentStatus=null  failureReason=null
# [3s]  status=FAILED   orderId=null  shipmentStatus=null  failureReason=MISSING_SKU=[SKU-INVALID]
# === Negative Smoke test 결과 ===
# [PASS] status=FAILED
# [PASS] orderId=null
# [PASS] shipmentStatus=null
# [PASS] failureReason=MISSING_SKU=[SKU-INVALID] (MISSING_SKU 포함)
```

성공 조건: `status=FAILED` && `orderId=null` && `shipmentStatus=null` && `failureReason` 에 `MISSING_SKU` 포함  
종료 코드: 성공 `0` / 실패(타임아웃 또는 조건 불일치) `1`

```bash
# 수동 검증 (단계별)

# 1. invalid SKU로 주문 생성
curl -s -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-INVALID","quantity":1}]}' | jq .
# → {"data": {"idemKey": "...", ...}, "message": "OK"}

# 2. 상태 조회 (idemKey 교체)
curl -s http://localhost:8083/api/orders/status/<IDEM_KEY> | jq .
# 최초 (PENDING):
# {"data": {"idemKey": "...", "status": "PENDING", "orderId": null, "shipmentStatus": null, "failureReason": null}}
# 실패 처리 후 (FAILED):
# {"data": {"idemKey": "...", "status": "FAILED", "orderId": null, "shipmentStatus": null, "failureReason": "MISSING_SKU=[SKU-INVALID]"}}
```
