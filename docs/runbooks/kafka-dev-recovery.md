# Kafka/Zookeeper 개발환경 복구 Runbook

대상 환경: Minikube (`ecommerce` namespace)
도구: `scripts/check-kafka-dev.sh`, `scripts/reset-kafka-dev.sh`

---

## 언제 실행하는가

| 증상 | 권장 조치 |
|------|----------|
| `kafka-0` / `zookeeper-0` pod가 `CrashLoopBackOff` 또는 `Error` 상태 | check → reset |
| Kafka consumer lag가 급격히 증가하고 pod 재시작이 반복됨 | check → reset |
| `kubectl logs kafka-0` 에서 Zookeeper 연결 실패 반복 | check → reset |
| PVC가 Bound 상태이나 마운트 오류 발생 | reset |
| Minikube를 새로 시작한 후 Kafka가 기동되지 않음 | reset |
| 토픽/메시지를 완전히 초기화하고 싶을 때 | reset |

> 운영 환경에서는 절대 사용하지 않는다. 개발환경 전용 도구이다.

---

## 초기화되는 데이터

`reset-kafka-dev.sh` 실행 시 다음이 **영구 삭제**된다.

- Kafka StatefulSet (`kafka`) 및 모든 pod
- Zookeeper StatefulSet (`zookeeper`) 및 모든 pod
- PVC `data-kafka-0` — Kafka 로그/메시지/토픽 데이터
- PVC `data-zookeeper-0` — Zookeeper 메타데이터 (브로커 등록, consumer group offset)
- Minikube hostPath 볼륨 (`/tmp/hostpath-provisioner/ecommerce/data-kafka-*/`)

초기화 후 Kafka는 빈 상태로 재기동된다. 기존 consumer group offset은 모두 사라진다.

---

## 실행 순서

### 1단계: 현재 상태 확인

```bash
./scripts/check-kafka-dev.sh
```

FAIL 항목이 있으면 2단계로 진행한다. PASS이면 애플리케이션 설정을 먼저 점검한다.

### 2단계: Kafka/Zookeeper 초기화

```bash
./scripts/reset-kafka-dev.sh
```

실행 중 `정말 초기화할까요? [y/N]` 프롬프트가 표시된다. `y`를 입력해야 진행된다.

스크립트가 수행하는 작업 순서:
1. Minikube 환경 및 namespace 존재 여부 검증
2. StatefulSet `kafka`, `zookeeper` 삭제 → pod 종료 대기
3. PVC `data-kafka-0`, `data-zookeeper-0` 삭제
4. Minikube hostPath 볼륨 데이터 정리
5. `deployment/infra/zookeeper.yaml` 재적용
6. `deployment/infra/kafka.yaml` 재적용
7. rollout 완료 및 endpoints 확인

### 3단계: 상태 재확인

```bash
./scripts/check-kafka-dev.sh
```

모든 항목이 PASS이면 복구 완료이다.

### 4단계: 애플리케이션 재배포

Kafka가 초기화되면 consumer group offset이 사라지므로 `logistics-api`와 `order-api`를 재배포해야 한다.

```bash
# 프로젝트 루트에서 실행
./scripts/redeploy-api.sh logistics-api
./scripts/redeploy-api.sh order-api
```

재배포 순서는 `logistics-api` → `order-api` 권장이다.  
`logistics-api`가 shipment-event를 발행하고 `order-api`가 소비하는 흐름이기 때문이다.

---

## 주의사항

- `reset-kafka-dev.sh`는 `redeploy-api.sh`와 독립된 도구이다. CI 또는 일반 배포 흐름에 포함하지 않는다.
- `scripts/redeploy-api.sh`는 수정하지 않는다.
- 초기화 후 Kafka 토픽은 애플리케이션이 재기동될 때 자동 생성된다 (auto.create.topics.enable 기본값).
- Minikube를 `minikube delete` 후 재시작한 경우에는 reset 없이 `kubectl apply`만 실행해도 된다.

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| `scripts/check-kafka-dev.sh` | 상태 점검 (read-only) |
| `scripts/reset-kafka-dev.sh` | 파괴적 초기화 (확인 후 실행) |
| `scripts/redeploy-api.sh` | 애플리케이션 재배포 (기존 도구, 수정 금지) |
| `deployment/infra/kafka.yaml` | Kafka StatefulSet + Service manifest |
| `deployment/infra/zookeeper.yaml` | Zookeeper StatefulSet + Service manifest |
