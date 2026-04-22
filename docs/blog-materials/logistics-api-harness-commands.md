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
```

---

## GitHub Actions CI Gate 트리거 확인

```bash
# 변경사항 커밋 후 push → GitHub Actions 자동 트리거
git add .
bash scripts/claude-guardrails.sh   # 로컬 사전 확인
git commit -m "feat: add logistics-api"
git push origin dev-logitical

# GitHub Actions에서 다음 job 순서로 실행됨:
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
