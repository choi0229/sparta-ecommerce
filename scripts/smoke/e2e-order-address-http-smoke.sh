#!/usr/bin/env bash
#
# e2e-order-address-http-smoke.sh
#
# 사전 조건:
#   - minikube 가 실행 중이어야 한다.
#   - order-api Deployment 가 ecommerce namespace 에 배포되어 있어야 한다.
#   - ./address-api/Dockerfile.mock 과 ./address-api/mappings/ 가 존재해야 한다.
#   - docker, kubectl, minikube 명령이 PATH 에 있어야 한다.
#   - 프로젝트 루트 디렉토리에서 실행해야 한다.
#
# 실행 방법:
#   chmod +x scripts/smoke/e2e-order-address-http-smoke.sh
#   bash scripts/smoke/e2e-order-address-http-smoke.sh
#
# 성공 조건:
#   [5/7] addressId=1   → status=CREATED, shipmentStatus=READY
#   [6/7] addressId=999 → HTTP 404, errorCode=ADDRESS_NOT_FOUND
#   [7/7] addressId=503 → HTTP 500, errorCode=ADDRESS_LOOKUP_FAILED
#
# 종료 코드:
#   0 = 성공
#   1 = 실패 (빌드 오류, 배포 실패, 예상 응답 불일치, 타임아웃 포함)

set -euo pipefail

ORDER_API="http://localhost:8083"
NAMESPACE="ecommerce"
IMAGE_NAME="sparta-msa-final-project-address-api:mock"
POLL_INTERVAL=3
# Kafka/Outbox 기반 비동기 처리라 self-hosted runner/minikube 환경에서 30초를 초과할 수 있음
MAX_WAIT_SECONDS=60
PF_PID=""
ORDER_API_POD=""
PF_LOG=$(mktemp)
PF_READY=false
RUN_FAILED=true
RESP_TMP=$(mktemp)

# ── 프로젝트 루트 확인 ─────────────────────────────────────────────────────────
if [[ ! -d "./address-api" ]]; then
  echo "[FAIL] ./address-api 디렉토리를 찾을 수 없습니다. 프로젝트 루트에서 실행해 주세요."
  exit 1
fi

# ── jq 유무 감지 ───────────────────────────────────────────────────────────────
if command -v jq &>/dev/null; then
  USE_JQ=true
else
  USE_JQ=false
  echo "[WARN] jq 미설치 — grep/sed fallback 사용"
fi

extract() {
  local json="$1" key="$2"
  if $USE_JQ; then
    echo "$json" | jq -r ".data.${key} // empty"
  else
    echo "$json" | grep -o "\"${key}\"[[:space:]]*:[[:space:]]*[^,}]*" \
                 | sed 's/.*:[[:space:]]*//' \
                 | tr -d '"'
  fi
}

extract_error() {
  local json="$1"
  if $USE_JQ; then
    echo "$json" | jq -r ".error.errorCode // empty"
  else
    echo "$json" | grep -o '"errorCode"[[:space:]]*:[[:space:]]*"[^"]*"' \
                 | sed 's/.*:[[:space:]]*//' \
                 | tr -d '"'
  fi
}

# 해당 포트를 사용 중인 kubectl port-forward가 있으면 종료한다.
# kubectl port-forward가 아닌 프로세스는 건드리지 않고 즉시 실패한다.
kill_existing_kubectl_pf() {
  local port="$1"
  local existing_pid
  existing_pid=$(lsof -ti :"${port}" 2>/dev/null | head -1 || true)
  if [[ -z "$existing_pid" ]]; then
    return 0
  fi
  local proc_cmd
  proc_cmd=$(ps -p "$existing_pid" -o args= 2>/dev/null || true)
  if [[ "$proc_cmd" == *"kubectl port-forward"* ]]; then
    echo "[INFO] Port ${port} 에 기존 kubectl port-forward (PID=${existing_pid}) 발견 — 종료 중..."
    kill "$existing_pid" 2>/dev/null || true
    sleep 1
    echo "[OK] 기존 kubectl port-forward 종료 완료"
  else
    echo "[FAIL] Port ${port} 를 사용 중인 프로세스(PID=${existing_pid})가 kubectl port-forward가 아닙니다."
    echo "       cmd: ${proc_cmd:-unknown}"
    echo "       수동으로 종료 후 재실행하세요."
    exit 1
  fi
}

cleanup() {
  echo ""
  echo "=== [cleanup] order-api stub 모드 복원 ==="
  kubectl set env deployment/order-api -n "${NAMESPACE}" \
    ADDRESS_CLIENT_MODE- \
    ADDRESS_CLIENT_BASE_URL- \
    ADDRESS_CLIENT_CONNECT_TIMEOUT_MS- \
    ADDRESS_CLIENT_READ_TIMEOUT_MS- 2>/dev/null || true
  echo "[cleanup] stub 모드 env 제거 완료"

  if [[ -n "${PF_PID}" ]]; then
    kill "${PF_PID}" 2>/dev/null || true
    echo "[cleanup] port-forward (PID=${PF_PID}) 종료"
  fi

  if [[ "${RUN_FAILED}" == "true" && -f "${PF_LOG}" ]]; then
    echo ""
    echo "=== [cleanup] port-forward 최근 로그 (tail -50) ==="
    tail -50 "${PF_LOG}" || true
  fi
}

trap cleanup EXIT

# ── 1단계: address-api 이미지 빌드 및 minikube 로드 ───────────────────────────
echo ""
echo "=== [1/7] mock address-api 이미지 빌드 및 minikube 로드 ==="

docker build -f ./address-api/Dockerfile.mock -t "${IMAGE_NAME}" ./address-api
echo "[OK] docker build 완료: ${IMAGE_NAME}"

minikube image load "${IMAGE_NAME}"
echo "[OK] minikube image load 완료"

# ── 2단계: address-api 배포 및 readiness 확인 ─────────────────────────────────
echo ""
echo "=== [2/7] address-api Deployment 적용 ==="

kubectl apply -f deployment/mock-address-api/
kubectl rollout status deployment/mock-address-api -n "${NAMESPACE}" --timeout=120s
echo "[OK] mock-address-api rollout 완료"

# ── 3단계: order-api http 모드 전환 및 rollout 대기 ───────────────────────────
echo ""
echo "=== [3/7] order-api http 모드 전환 ==="

kubectl set env deployment/order-api -n "${NAMESPACE}" \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://mock-address-api-svc:8090 \
  ADDRESS_CLIENT_CONNECT_TIMEOUT_MS=1000 \
  ADDRESS_CLIENT_READ_TIMEOUT_MS=2000

kubectl rollout status deployment/order-api -n "${NAMESPACE}" --timeout=120s

ORDER_API_POD=$(kubectl get pods -n "${NAMESPACE}" -l app=order-api \
  --sort-by=.metadata.creationTimestamp \
  -o custom-columns=NAME:.metadata.name --no-headers | tail -n 1)
echo "[OK] 최신 order-api pod: ${ORDER_API_POD}"

kubectl wait --for=condition=ready pod/"${ORDER_API_POD}" -n "${NAMESPACE}" --timeout=120s
echo "[OK] order-api rollout 완료 및 pod Ready 확인 (mode=http)"

# ── 4단계: order-api port-forward 시작 ───────────────────────────────────────
echo ""
echo "=== [4/7] order-api port-forward 시작 (localhost:8083) ==="

kill_existing_kubectl_pf 8083

kubectl port-forward pod/"${ORDER_API_POD}" 8083:8083 -n "${NAMESPACE}" >"${PF_LOG}" 2>&1 &
PF_PID=$!

# PF 시작 직후 프로세스 생존 확인 및 포트 충돌 감지
sleep 2
if ! kill -0 "${PF_PID}" 2>/dev/null; then
  echo "[FAIL] port-forward 프로세스(PID=${PF_PID})가 시작 직후 종료되었습니다."
  tail -20 "${PF_LOG}" || true
  exit 1
fi
if grep -qiE "address already in use|unable to listen" "${PF_LOG}" 2>/dev/null; then
  echo "[FAIL] port-forward 시작 실패 — 포트 충돌 감지"
  tail -20 "${PF_LOG}" || true
  kill "${PF_PID}" 2>/dev/null || true
  exit 1
fi

for i in $(seq 1 30); do
  sleep 1
  if curl -sS --max-time 2 "${ORDER_API}/actuator/health" >/dev/null 2>&1; then
    PF_READY=true
    break
  fi
done

if [[ "${PF_READY}" != "true" ]]; then
  echo "[FAIL] port-forward 가 30초 내에 응답하지 않습니다."
  echo "       port-forward 로그:"
  tail -50 "${PF_LOG}" || true
  exit 1
fi

echo "[OK] port-forward 준비 완료 (PID=${PF_PID})"

# ── 5단계: addressId=1 → 성공 경로 (async polling) ───────────────────────────
echo ""
echo "=== [5/7] addressId=1 성공 경로 검증 ==="

if ! CREATE_RESP=$(curl -s -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"addressId":1}'); then
  echo "[FAIL] POST /api/orders 요청 실패 — port-forward 가 동작하지 않거나 order-api 응답 없음"
  echo "       port-forward 로그:"
  tail -50 "${PF_LOG}" || true
  exit 1
fi

echo "응답: ${CREATE_RESP}"

IDEM_KEY=$(extract "${CREATE_RESP}" "idemKey")
if [[ -z "${IDEM_KEY}" ]]; then
  echo "[FAIL] idemKey를 추출하지 못했습니다."
  exit 1
fi
echo "[OK] idemKey = ${IDEM_KEY}"

elapsed=0
while true; do
  STATUS_RESP=$(curl -s "${ORDER_API}/api/orders/status/${IDEM_KEY}")
  STATUS=$(extract "${STATUS_RESP}" "status")
  SHIPMENT=$(extract "${STATUS_RESP}" "shipmentStatus")
  echo "[${elapsed}s] status=${STATUS:-null}  shipmentStatus=${SHIPMENT:-null}"

  if [[ "${STATUS}" == "CREATED" && "${SHIPMENT}" == "READY" ]]; then
    break
  fi

  if [[ ${elapsed} -ge ${MAX_WAIT_SECONDS} ]]; then
    echo "[FAIL] ${MAX_WAIT_SECONDS}초 안에 status=CREATED, shipmentStatus=READY 에 도달하지 못했습니다."
    echo "       최종 응답: ${STATUS_RESP}"
    echo ""
    echo "=== [진단] 클러스터 pod 상태 ==="
    kubectl get pods -n "${NAMESPACE}" --no-headers 2>/dev/null || true
    echo ""
    echo "=== [진단] order-api 최근 로그 (20줄) ==="
    kubectl logs -n "${NAMESPACE}" -l app=order-api --tail=20 --since=2m 2>/dev/null || true
    echo ""
    echo "=== [진단] logistics-api 최근 로그 (20줄) ==="
    kubectl logs -n "${NAMESPACE}" -l app=logistics-api --tail=20 --since=2m 2>/dev/null || true
    echo ""
    echo "=== [진단] product-api 최근 로그 (20줄) ==="
    kubectl logs -n "${NAMESPACE}" -l app=product-api --tail=20 --since=2m 2>/dev/null || true
    exit 1
  fi

  sleep "${POLL_INTERVAL}"
  elapsed=$((elapsed + POLL_INTERVAL))
done

echo "[OK] addressId=1 → status=CREATED, shipmentStatus=READY"

# ── 6단계: addressId=999 → ADDRESS_NOT_FOUND (HTTP 404) ───────────────────────
echo ""
echo "=== [6/7] addressId=999 → ADDRESS_NOT_FOUND 검증 ==="

HTTP_CODE=$(curl -s -o "${RESP_TMP}" -w "%{http_code}" \
  -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"addressId":999}')

RESP_BODY=$(cat "${RESP_TMP}")
echo "HTTP ${HTTP_CODE}: ${RESP_BODY}"

ERROR_CODE=$(extract_error "${RESP_BODY}")

if [[ "${HTTP_CODE}" != "404" ]]; then
  echo "[FAIL] 예상 HTTP 404, 실제 HTTP ${HTTP_CODE}"
  exit 1
fi

if [[ "${ERROR_CODE}" != "ADDRESS_NOT_FOUND" ]]; then
  echo "[FAIL] 예상 errorCode=ADDRESS_NOT_FOUND, 실제 ${ERROR_CODE:-null}"
  exit 1
fi

echo "[OK] addressId=999 → HTTP 404, errorCode=ADDRESS_NOT_FOUND"

# ── 7단계: addressId=503 → ADDRESS_LOOKUP_FAILED (HTTP 500) ──────────────────
echo ""
echo "=== [7/7] addressId=503 → ADDRESS_LOOKUP_FAILED 검증 ==="

HTTP_CODE=$(curl -s -o "${RESP_TMP}" -w "%{http_code}" \
  -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"addressId":503}')

RESP_BODY=$(cat "${RESP_TMP}")
echo "HTTP ${HTTP_CODE}: ${RESP_BODY}"

ERROR_CODE=$(extract_error "${RESP_BODY}")

if [[ "${HTTP_CODE}" != "500" ]]; then
  echo "[FAIL] 예상 HTTP 500, 실제 HTTP ${HTTP_CODE}"
  exit 1
fi

if [[ "${ERROR_CODE}" != "ADDRESS_LOOKUP_FAILED" ]]; then
  echo "[FAIL] 예상 errorCode=ADDRESS_LOOKUP_FAILED, 실제 ${ERROR_CODE:-null}"
  exit 1
fi

echo "[OK] addressId=503 → HTTP 500, errorCode=ADDRESS_LOOKUP_FAILED"

RUN_FAILED=false

# ── 최종 결과 ──────────────────────────────────────────────────────────────────
echo ""
echo "=== address http mode smoke test 결과 ==="
echo "[PASS] [5/7] addressId=1   → status=CREATED, shipmentStatus=READY"
echo "[PASS] [6/7] addressId=999 → HTTP 404, errorCode=ADDRESS_NOT_FOUND"
echo "[PASS] [7/7] addressId=503 → HTTP 500, errorCode=ADDRESS_LOOKUP_FAILED"
echo "       idemKey = ${IDEM_KEY}"
exit 0
