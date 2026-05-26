---
name: deploy-api
description: Minikube/Kubernetes 환경에 product-api, order-api, inventory-api, logistics-api 변경사항을 반영할 때 사용합니다. 사용자가 배포, 재배포, 이미지 빌드, minikube image load, rollout 반영을 요청한 경우 사용합니다.
---

# deploy-api Skill

이 Skill은 API 서비스 변경사항을 Mac/Linux Bash 환경에서 Minikube/Kubernetes에 반영할 때만 사용합니다.

사용자가 명시적으로 배포 반영을 요청하지 않았다면 이 절차를 실행하지 않습니다.

## 지원 서비스

- `product-api`
- `order-api`
- `inventory-api`
- `logistics-api`

## 기본 원칙

`./gradlew test`는 테스트 검증용입니다.

실행 중인 Kubernetes deployment에 변경사항을 반영하려면 테스트 명령만으로는 부족합니다.

실제 반영 흐름은 다음과 같습니다.

```text
서비스 디렉터리 이동
→ Kubernetes deployment scale down
→ 기존 Minikube image 제거
→ bootJar 생성
→ Docker image build
→ Minikube image load
→ Kubernetes deployment scale up
→ rollout status 확인
```

## 실행 명령

Mac/Linux Bash 기준으로 다음 스크립트를 사용합니다.

```bash
./scripts/redeploy-api.sh order-api
```

서비스별 실행 예시는 다음과 같습니다.

```bash
./scripts/redeploy-api.sh product-api
./scripts/redeploy-api.sh order-api
./scripts/redeploy-api.sh inventory-api
./scripts/redeploy-api.sh logistics-api
```

replica 수를 직접 지정하려면 두 번째 인자로 전달합니다.

```bash
./scripts/redeploy-api.sh order-api 2
```

## 스크립트 실행 전 확인

스크립트에 실행 권한이 없다면 다음 명령어를 먼저 실행합니다.

```bash
chmod +x scripts/redeploy-api.sh
```

## 스크립트 동작 상세

`scripts/redeploy-api.sh`의 실제 동작 기준입니다.

- **namespace**: `ecommerce` 고정
- **기본 replicas**: `2` (두 번째 인자로 변경 가능)
- **image 이름 규칙**: `sparta-msa-final-project-{service}:latest`
- **minikube image rm**: 실패해도 `|| true`로 계속 진행
- **그 외 모든 단계**: `set -e`로 실패 시 즉시 중단

kubectl 명령 예시에서 `-n ecommerce`를 누락하지 않습니다.

## 주의사항

`./gradlew bootJar -x test`는 테스트를 건너뛰고 jar를 생성합니다.

따라서 이 명령을 실행했다고 해서 테스트가 통과한 것은 아닙니다.

테스트 검증이 필요한 경우 별도로 다음 명령을 실행합니다.

```bash
cd order-api
./gradlew test
```

배포 반영 후에는 rollout status를 반드시 확인합니다.

배포 실패 시 다음 명령어로 상태를 확인합니다.

```bash
kubectl get pods -n ecommerce
kubectl describe pod <pod-name> -n ecommerce
kubectl logs <pod-name> -n ecommerce
```

Kubernetes deployment scale 조정, Minikube image 삭제, Docker image build, Minikube image load는 실행 환경에 영향을 주는 작업입니다.

따라서 사용자가 명시적으로 배포 반영을 요청한 경우에만 실행합니다.