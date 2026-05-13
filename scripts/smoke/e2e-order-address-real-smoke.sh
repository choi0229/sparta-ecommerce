#!/usr/bin/env bash
#
# e2e-order-address-real-smoke.sh
#
# 사전 조건:
#   - minikube 가 실행 중이어야 한다.
#   - ecommerce namespace 에 다음 리소스가 배포되어 있어야 한다.
#       deployment/order-api
#       deployment/address-api
#       statefulset/address-db
#   - kubectl, curl 명령이 PATH 에 있어야 한다.
#   - 프로젝트 루트 디렉토리에서 실행해야 한다.
#
# 실행 방법:
#   chmod +x scripts/smoke/e2e-order-address-real-smoke.sh
#   bash scripts/smoke/e2e-order-address-real-smoke.sh
#
# 성공 조건:
#   [4/6] POST /addresses(userId=9001) → 주소 생성 성공, addressId 추출
#   [5/6] POST /api/orders(addressId=<생성된 ID>) → status=CREATED, shipmentStatus=READY
#   [6/6] DELETE /addresses/{id} → 204, GET → 404, POST /api/orders → HTTP 404 + ADDRESS_NOT_FOUND
#
# 종료 코드:
#   0 = 성공
#   1 = 실패 (사전 조건 불충족, 배포 실패, 예상 응답 불일치, 타임아웃 포함)

set -euo pipefail

ORDER_API="http://localhost:8083"
ADDRESS_API="http://localhost:8090"
NAMESPACE="ecommerce"
SMOKE_USER_ID=9001
POLL_INTERVAL=3
TIMEOUT=60
ORDER_PF_PID=""
ADDR_PF_PID=""
ORDER_API_POD=""
ORDER_PF_LOG=$(mktemp)
ADDR_PF_LOG=$(mktemp)
ORDER_PF_READY=false
ADDR_PF_READY=false
RUN_FAILED=true
RESP_TMP=$(mktemp)
CREATED_ADDRESS_ID=""

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

# order-api 응답: {"data": {"key": "value"}}
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

# address-api 응답: {"id": 100, "userId": 1, ...} (data wrapper 없음)
extract_field() {
  local json="$1" key="$2"
  if $USE_JQ; then
    echo "$json" | jq -r ".${key} // empty"
  else
    echo "$json" | grep -o "\"${key}\"[[:space:]]*:[[:space:]]*[^,}]*" \
                 | sed 's/.*:[[:space:]]*//' \
                 | tr -d '"'
  fi
}

# order-api 오류 응답: {"error": {"errorCode": "...", "errorMessage": "..."}}
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
  echo "=== [cleanup] order-api real 모드 환경 변수 제거 ==="
  kubectl set env deployment/order-api -n "${NAMESPACE}" \
    ADDRESS_CLIENT_MODE- \
    ADDRESS_CLIENT_BASE_URL- \
    ADDRESS_CLIENT_CONNECT_TIMEOUT_MS- \
    ADDRESS_CLIENT_READ_TIMEOUT_MS- 2>/dev/null || true
  echo "[cleanup] env 제거 완료"

  if [[ -n "${ORDER_PF_PID}" ]]; then
    kill "${ORDER_PF_PID}" 2>/dev/null || true
    echo "[cleanup] order-api port-forward (PID=${ORDER_PF_PID}) 종료"
  fi

  if [[ -n "${ADDR_PF_PID}" ]]; then
    kill "${ADDR_PF_PID}" 2>/dev/null || true
    echo "[cleanup] address-api port-forward (PID=${ADDR_PF_PID}) 종료"
  fi

  if [[ "${RUN_FAILED}" == "true" ]]; then
    if [[ -f "${ORDER_PF_LOG}" ]]; then
      echo ""
      echo "=== [cleanup] order-api port-forward 로그 (tail -30) ==="
      tail -30 "${ORDER_PF_LOG}" || true
    fi
    if [[ -f "${ADDR_PF_LOG}" ]]; then
      echo ""
      echo "=== [cleanup] address-api port-forward 로그 (tail -30) ==="
      tail -30 "${ADDR_PF_LOG}" || true
    fi
  fi
}

trap cleanup EXIT

# ── 1단계: 사전 조건 확인 ──────────────────────────────────────────────────────
echo ""
echo "=== [1/6] 사전 조건 확인 ==="

for cmd in kubectl curl; do
  if ! command -v "${cmd}" &>/dev/null; then
    echo "[FAIL] 필수 명령어 '${cmd}' 를 찾을 수 없습니다."
    exit 1
  fi
done
echo "[OK] kubectl, curl 확인"

for resource in "deployment/order-api" "deployment/address-api" "statefulset/address-db"; do
  if ! kubectl get "${resource}" -n "${NAMESPACE}" &>/dev/null; then
    echo "[FAIL] ${resource} 가 ${NAMESPACE} namespace 에 존재하지 않습니다."
    exit 1
  fi
done
echo "[OK] deployment/order-api, deployment/address-api, statefulset/address-db 확인"

# ── 2단계: order-api real http 모드 전환 및 rollout 대기 ──────────────────────
echo ""
echo "=== [2/6] order-api real http 모드 전환 ==="

kubectl set env deployment/order-api -n "${NAMESPACE}" \
  ADDRESS_CLIENT_MODE=http \
  ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090 \
  ADDRESS_CLIENT_CONNECT_TIMEOUT_MS=1000 \
  ADDRESS_CLIENT_READ_TIMEOUT_MS=2000

kubectl rollout status deployment/order-api -n "${NAMESPACE}" --timeout=120s

ORDER_API_POD=$(kubectl get pods -n "${NAMESPACE}" -l app=order-api \
  --sort-by=.metadata.creationTimestamp \
  -o custom-columns=NAME:.metadata.name --no-headers | tail -n 1)
echo "[OK] 최신 order-api pod: ${ORDER_API_POD}"

kubectl wait --for=condition=ready pod/"${ORDER_API_POD}" -n "${NAMESPACE}" --timeout=120s
echo "[OK] order-api rollout 완료 및 pod Ready 확인 (mode=http, real address-api)"

# ── 3단계: port-forward 시작 (order-api + address-api) ────────────────────────
echo ""
echo "=== [3/6] port-forward 시작 (order-api:8083, address-api-svc:8090) ==="

kubectl port-forward pod/"${ORDER_API_POD}" 8083:8083 -n "${NAMESPACE}" >"${ORDER_PF_LOG}" 2>&1 &
ORDER_PF_PID=$!

kubectl port-forward svc/address-api-svc 8090:8090 -n "${NAMESPACE}" >"${ADDR_PF_LOG}" 2>&1 &
ADDR_PF_PID=$!

echo "[INFO] order-api port-forward PID=${ORDER_PF_PID}, address-api port-forward PID=${ADDR_PF_PID}"

for i in $(seq 1 30); do
  sleep 1
  if curl -sS --max-time 2 "${ORDER_API}/actuator/health" >/dev/null 2>&1; then
    ORDER_PF_READY=true
    break
  fi
done

if [[ "${ORDER_PF_READY}" != "true" ]]; then
  echo "[FAIL] order-api port-forward 가 30초 내에 응답하지 않습니다."
  exit 1
fi
echo "[OK] order-api port-forward 준비 완료"

for i in $(seq 1 30); do
  sleep 1
  if curl -sS --max-time 2 "${ADDRESS_API}/actuator/health" >/dev/null 2>&1; then
    ADDR_PF_READY=true
    break
  fi
done

if [[ "${ADDR_PF_READY}" != "true" ]]; then
  echo "[FAIL] address-api port-forward 가 30초 내에 응답하지 않습니다."
  exit 1
fi
echo "[OK] address-api port-forward 준비 완료"

# ── 4단계: 테스트 주소 생성 (userId=9001) ─────────────────────────────────────
echo ""
echo "=== [4/6] 테스트 주소 생성 (userId=${SMOKE_USER_ID}) ==="

CREATE_ADDR_RESP=$(curl -s -X POST "${ADDRESS_API}/addresses" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":${SMOKE_USER_ID},\"recipientName\":\"smoke-tester\",\"recipientAddress\":\"smoke-addr-real\",\"isDefault\":true}")

echo "응답: ${CREATE_ADDR_RESP}"

CREATED_ADDRESS_ID=$(extract_field "${CREATE_ADDR_RESP}" "id")
if [[ -z "${CREATED_ADDRESS_ID}" ]]; then
  echo "[FAIL] addressId 를 추출하지 못했습니다."
  exit 1
fi
echo "[OK] 테스트 주소 생성 완료 — addressId=${CREATED_ADDRESS_ID}"

# ── 5단계: 주문 생성 및 상태 polling (addressId=생성된 ID) ────────────────────
echo ""
echo "=== [5/6] 주문 생성 및 상태 조회 (addressId=${CREATED_ADDRESS_ID}) ==="

if ! CREATE_ORDER_RESP=$(curl -s -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"items\":[{\"sku\":\"SKU-TEST-001\",\"quantity\":1}],\"addressId\":${CREATED_ADDRESS_ID}}"); then
  echo "[FAIL] POST /api/orders 요청 실패 — port-forward 가 동작하지 않거나 order-api 응답 없음"
  exit 1
fi

echo "응답: ${CREATE_ORDER_RESP}"

IDEM_KEY=$(extract "${CREATE_ORDER_RESP}" "idemKey")
if [[ -z "${IDEM_KEY}" ]]; then
  echo "[FAIL] idemKey 를 추출하지 못했습니다."
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

echo "[OK] addressId=${CREATED_ADDRESS_ID} → status=CREATED, shipmentStatus=READY"

# ── 6단계: 주소 삭제 → 차단 검증 ─────────────────────────────────────────────
echo ""
echo "=== [6/6] 주소 삭제 후 ADDRESS_NOT_FOUND 검증 ==="

# 6-1: DELETE → 204
DELETE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "${ADDRESS_API}/addresses/${CREATED_ADDRESS_ID}")

if [[ "${DELETE_CODE}" != "204" ]]; then
  echo "[FAIL] DELETE /addresses/${CREATED_ADDRESS_ID} — 예상 HTTP 204, 실제 HTTP ${DELETE_CODE}"
  exit 1
fi
echo "[OK] DELETE /addresses/${CREATED_ADDRESS_ID} → HTTP 204"

# 6-2: GET 삭제된 주소 → 404
GET_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "${ADDRESS_API}/addresses/${CREATED_ADDRESS_ID}")

if [[ "${GET_CODE}" != "404" ]]; then
  echo "[FAIL] GET /addresses/${CREATED_ADDRESS_ID} — 예상 HTTP 404, 실제 HTTP ${GET_CODE}"
  exit 1
fi
echo "[OK] GET /addresses/${CREATED_ADDRESS_ID} (삭제 후) → HTTP 404"

# 6-3: 삭제된 addressId로 주문 생성 → ADDRESS_NOT_FOUND
HTTP_CODE=$(curl -s -o "${RESP_TMP}" -w "%{http_code}" \
  -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"items\":[{\"sku\":\"SKU-TEST-001\",\"quantity\":1}],\"addressId\":${CREATED_ADDRESS_ID}}")

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

echo "[OK] 삭제된 addressId=${CREATED_ADDRESS_ID} → HTTP 404, errorCode=ADDRESS_NOT_FOUND"

RUN_FAILED=false

# ── 최종 결과 ──────────────────────────────────────────────────────────────────
echo ""
echo "=== real address-api smoke test 결과 ==="
echo "[PASS] [4/6] POST /addresses(userId=${SMOKE_USER_ID}) → addressId=${CREATED_ADDRESS_ID} 생성"
echo "[PASS] [5/6] addressId=${CREATED_ADDRESS_ID} → status=CREATED, shipmentStatus=READY"
echo "[PASS] [6/6] 주소 삭제 후 → HTTP 204 / GET 404 / 주문 HTTP 404 ADDRESS_NOT_FOUND"
echo "       idemKey = ${IDEM_KEY}"
exit 0
