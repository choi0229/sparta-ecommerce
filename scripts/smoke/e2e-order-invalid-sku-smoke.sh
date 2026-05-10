#!/usr/bin/env bash
#
# e2e-order-invalid-sku-smoke.sh
#
# 사전 조건:
#   - order-api   가 localhost:8083 에서 응답 중이어야 한다.
#   - product-api 가 Kafka로 연결되어 order-api 의 productSnapshot-requested-event 를
#     수신하고 MISSING_SKU 실패 reply 를 반환할 수 있어야 한다.
#   - kubectl port-forward 또는 minikube service 로 포트가 열려 있어야 한다.
#
# 실행 방법:
#   chmod +x scripts/e2e-order-invalid-sku-smoke.sh
#   bash scripts/e2e-order-invalid-sku-smoke.sh
#
# 성공 조건:
#   status        == FAILED
#   orderId       == null
#   shipmentStatus == null
#   failureReason 에 MISSING_SKU 포함
#
# 종료 코드:
#   0 = 성공 (failure path 정상 동작 확인)
#   1 = 실패 (타임아웃 또는 예상 응답 불일치)

set -euo pipefail

ORDER_API="http://localhost:8083"
POLL_INTERVAL=3   # 초
TIMEOUT=30        # 초

# ── jq 유무 감지 ────────────────────────────────────────────────────────────────
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

is_null() {
  local val="$1"
  [[ -z "$val" || "$val" == "null" ]]
}

# ── 1단계: invalid SKU로 주문 생성 ──────────────────────────────────────────────
echo ""
echo "=== [1/2] POST /api/orders — invalid SKU로 주문 생성 ==="
CREATE_BODY='{"userId":1,"items":[{"sku":"SKU-INVALID","quantity":1}],"shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울시 강남구 테헤란로 1"}}'

CREATE_RESP=$(curl -s -X POST "${ORDER_API}/api/orders" \
  -H "Content-Type: application/json" \
  -d "${CREATE_BODY}")

echo "응답: ${CREATE_RESP}"

IDEM_KEY=$(extract "$CREATE_RESP" "idemKey")

if [[ -z "$IDEM_KEY" ]]; then
  echo "[FAIL] idemKey를 추출하지 못했습니다."
  exit 1
fi

echo "[OK] idemKey = ${IDEM_KEY}"

# ── 2단계: polling (status=FAILED 도달 대기) ─────────────────────────────────────
echo ""
echo "=== [2/2] GET /api/orders/status/${IDEM_KEY} — 목표: status=FAILED — 폴링 시작 (최대 ${TIMEOUT}초) ==="

elapsed=0
FAILURE_REASON=""

while true; do
  STATUS_RESP=$(curl -s "${ORDER_API}/api/orders/status/${IDEM_KEY}")
  STATUS=$(extract "$STATUS_RESP" "status")
  ORDER_ID=$(extract "$STATUS_RESP" "orderId")
  SHIPMENT_STATUS=$(extract "$STATUS_RESP" "shipmentStatus")
  FAILURE_REASON=$(extract "$STATUS_RESP" "failureReason")

  echo "[${elapsed}s] status=${STATUS:-null}  orderId=${ORDER_ID:-null}  shipmentStatus=${SHIPMENT_STATUS:-null}  failureReason=${FAILURE_REASON:-null}"

  if [[ "$STATUS" == "FAILED" ]]; then
    if ! is_null "$ORDER_ID"; then
      echo "[FAIL] status=FAILED 이지만 orderId=${ORDER_ID} (null 이어야 합니다)"
      exit 1
    fi
    if ! is_null "$SHIPMENT_STATUS"; then
      echo "[FAIL] status=FAILED 이지만 shipmentStatus=${SHIPMENT_STATUS} (null 이어야 합니다)"
      exit 1
    fi
    if [[ "$FAILURE_REASON" != *"MISSING_SKU"* ]]; then
      echo "[FAIL] failureReason='${FAILURE_REASON}' 에 'MISSING_SKU' 가 포함되어 있지 않습니다."
      exit 1
    fi
    break
  fi

  if [[ $elapsed -ge $TIMEOUT ]]; then
    echo ""
    echo "[FAIL] ${TIMEOUT}초 안에 status=FAILED 에 도달하지 못했습니다."
    echo "       최종 응답: ${STATUS_RESP}"
    exit 1
  fi

  sleep "$POLL_INTERVAL"
  elapsed=$((elapsed + POLL_INTERVAL))
done

# ── 최종 결과 ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Negative Smoke test 결과 ==="
echo "[PASS] status=FAILED"
echo "[PASS] orderId=null"
echo "[PASS] shipmentStatus=null"
echo "[PASS] failureReason=${FAILURE_REASON} (MISSING_SKU 포함)"
echo "       idemKey = ${IDEM_KEY}"
exit 0
