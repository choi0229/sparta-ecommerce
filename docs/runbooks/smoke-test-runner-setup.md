# Runbook: Smoke Test Self-Hosted Runner 준비

이 문서는 `.github/workflows/smoke-tests.yml`을 실행하기 위해 self-hosted runner에 필요한 준비 사항을 설명합니다.

---

## Runner에 필요한 것

| 항목                      | 확인 명령                                            | 비고 |
|-------------------------|--------------------------------------------------|---|
| `kubectl`               | `kubectl version --client`                       | 클러스터 버전과 맞는 kubectl |
| `curl`                  | `curl --version`                                 | smoke script에서 HTTP 호출에 사용 |
| `jq`                    | `jq --version`                                   | 미설치 시 grep/sed fallback 동작 (권장 설치) |
| kubeconfig              | `kubectl cluster-info`                           | 클러스터 접근 가능한 context가 설정되어 있어야 함 |
| 네임스페이스 `ecommerce`      | `kubectl get ns ecommerce`                       | |
| `svc/order-api-svc`     | `kubectl get svc order-api-svc -n ecommerce`     | |
| `svc/logistics-api-svc` | `kubectl get svc logistics-api-svc -n ecommerce` | |

---

## 실행 전 수동 점검 항목

```bash
# 1. 클러스터 접근
kubectl cluster-info

# 2. 서비스 존재 확인
kubectl get svc -n ecommerce

# 3. pod 상태 확인
kubectl get pods -n ecommerce

# 4. port-forward 수동 테스트
kubectl port-forward svc/order-api-svc 8083:8083 -n ecommerce &
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce &
curl -s http://localhost:8084/actuator/health | jq .
# 확인 후 정리
kill %1 %2
```

---

## GitHub Actions에서 실행하는 법

1. GitHub 저장소 → **Actions** 탭
2. **Smoke Tests** 워크플로 선택
3. **Run workflow** 클릭
4. `target` 선택:
   - `happy` — 정상 주문 → 배송 상태 검증만
   - `negative` — invalid SKU → FAILED 검증만
   - `all` (기본) — 두 시나리오 모두 실행

---

## 워크플로 step 흐름

```
Checkout
  └─ [Preflight] kubectl/curl/jq 확인, 클러스터 접근, svc 존재 확인
       └─ [Port-forward] order-api:8083, logistics-api:8084 시작 + 응답 대기
            ├─ [happy] e2e-order-shipment-smoke.sh  (target=happy|all)
            ├─ [negative] e2e-order-invalid-sku-smoke.sh  (target=negative|all)
            └─ [Teardown] port-forward 프로세스 종료 (always 실행)
```

---

## 실패 시 확인 포인트

| 실패 step | 원인 가능성 | 확인 명령 |
|---|---|---|
| `[Preflight]` kubectl | runner에 kubectl 미설치 | `which kubectl` |
| `[Preflight]` 클러스터 접근 불가 | kubeconfig 누락 또는 클러스터 중단 | `kubectl cluster-info` |
| `[Preflight]` svc not found | 서비스 이름 불일치 또는 미배포 | `kubectl get svc -n ecommerce` |
| `[Port-forward]` 20초 타임아웃 | pod가 Running 상태 아님 | `kubectl get pods -n ecommerce` |
| `[happy]` 폴링 타임아웃 | Kafka 연결 문제 또는 서비스 오류 | 각 서비스 로그 확인 |
| `[negative]` MISSING_SKU 미포함 | product-api Kafka consumer 오류 | product-api 로그 확인 |
