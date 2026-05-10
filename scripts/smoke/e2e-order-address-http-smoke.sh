#!/usr/bin/env bash
#
# e2e-order-address-http-smoke.sh
#
# 사전 조건:
#   - minikube 가 실행 중이어야 한다.
#   - order-api Deployment 가 ecommerce namespace 에 배포되어 있어야 한다.
#   - ./address-api/Dockerfile 과 ./address-api/mappings/ 가 존재해야 한다.
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
IMAGE_NAME="sparta-msa-final-project-address-api:latest"
POLL_INTERVAL=3
TIMEOUT=60
PF_PID=""
RESP_TMP="/tmp/addr_smoke_resp.json"

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
}

trap cleanup EXIT

# ── 1단계: address-api 이미지 빌드 및 minikube 로드 ───────────────────────────
echo ""
echo "=== [1/7] mock address-api 이미지 빌드 및 minikube 로드 ==="

docker build -t "${IMAGE_NAME}" ./address-api
echo "[OK] docker build 완료: ${IMAGE_NAME}"

minikube image load "${IMAGE_NAME}"
echo "[OK] minikube image load 완료"

# ── 2단계: address-api 배포 및 readiness 확인 ─────────────────────────────────
echo ""
echo "=== [2/7] address-api Deployment 적용 ==="

kubectl apply -f deployment/address-api/
kubectl rollout status deployment/address-api -n "${NAMESPACE}" --timeout=120s
echo "[OK] address-api rollout 완료"

# ── 3단계: order-api http 모드 전환 및 rollout 대기 ───────────────────────────
echo ""
echo "=== [3/7] order-api http 모드 전환 ==="

kubectl set env deployment/order-api -n "${NAMESPACE}" \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090 \
  ADDRESS_CLIENT_CONNECT_TIMEOUT_MS=1000 \
  ADDRESS_CLIENT_READ_TIMEOUT_MS=2000

kubectl rollout status deployment/order-api -n "${NAMESPACE}" --timeout=120s
echo "[OK] order-api rollout 완료 (mode=http)"

# ── 4단계: order-api port-forward 시작 ───────────────────────────────────────
echo ""
echo "=== [4/7] order-api port-forward 시작 (localhost:8083) ==="

kubectl port-forward svc/order-api-svc 8083:8083 -n "${NAMESPACE}" &>/dev/null &
PF_PID=$!

for i in $(seq 1 15); do
  sleep 1
  if curl -s --max-time 1 "${ORDER_API}/actuator/health" &>/dev/null; then
    break
  fi
done

echo "[OK] port-forward 준비 완료 (PID=${PF_PID})"

# ── 5단계: addressId=1 → 성공 경로 (async polling) ───────────────────────────
echo ""
echo "=== [5/7] addressId=1 성공 경로 검증 ==="

CREATE_RESP=$(curl -s -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"addressId":1}')

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

  if [[ ${elapsed} -ge ${TIMEOUT} ]]; then
    echo "[FAIL] ${TIMEOUT}초 안에 status=CREATED, shipmentStatus=READY 에 도달하지 못했습니다."
    echo "       최종 응답: ${STATUS_RESP}"
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

# ── 최종 결과 ──────────────────────────────────────────────────────────────────
echo ""
echo "=== address http mode smoke test 결과 ==="
echo "[PASS] [5/7] addressId=1   → status=CREATED, shipmentStatus=READY"
echo "[PASS] [6/7] addressId=999 → HTTP 404, errorCode=ADDRESS_NOT_FOUND"
echo "[PASS] [7/7] addressId=503 → HTTP 500, errorCode=ADDRESS_LOOKUP_FAILED"
echo "       idemKey = ${IDEM_KEY}"
exit 0
