#!/usr/bin/env bash
#
# e2e-order-shipment-smoke.sh
#
# 사전 조건:
#   - order-api  가 localhost:8083 에서 응답 중이어야 한다.
#   - logistics-api 가 order-api와 Kafka로 연결되어 있어야 한다.
#   - kubectl port-forward 또는 minikube service 로 포트가 열려 있어야 한다.
#
# 실행 방법:
#   chmod +x scripts/e2e-order-shipment-smoke.sh
#   bash scripts/e2e-order-shipment-smoke.sh
#
# 성공 조건:
#   status == CREATED  &&  shipmentStatus == READY
#
# 종료 코드:
#   0 = 성공
#   1 = 실패 (타임아웃 포함)

set -euo pipefail

ORDER_API="http://localhost:8083"
POLL_INTERVAL=3   # 초
TIMEOUT=30        # 초

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
    # "key":"value" 패턴 추출 (공백 허용)
    echo "$json" | grep -o "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
                 | sed 's/.*"[^"]*"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/'
  fi
}

# ── 1단계: 주문 생성 ───────────────────────────────────────────────────────────
echo ""
echo "=== [1/3] POST /api/orders — 주문 생성 ==="
CREATE_BODY='{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}]}'

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

# ── 2단계: 상태 polling ────────────────────────────────────────────────────────
echo ""
echo "=== [2/3] GET /api/orders/status/${IDEM_KEY} — 폴링 시작 (최대 ${TIMEOUT}초) ==="

elapsed=0
while true; do
  STATUS_RESP=$(curl -s "${ORDER_API}/api/orders/status/${IDEM_KEY}")
  STATUS=$(extract "$STATUS_RESP" "status")
  SHIPMENT=$(extract "$STATUS_RESP" "shipmentStatus")

  echo "[${elapsed}s] status=${STATUS:-null}  shipmentStatus=${SHIPMENT:-null}"

  if [[ "$STATUS" == "CREATED" && "$SHIPMENT" == "READY" ]]; then
    break
  fi

  if [[ $elapsed -ge $TIMEOUT ]]; then
    echo ""
    echo "[FAIL] ${TIMEOUT}초 안에 목표 상태에 도달하지 못했습니다."
    echo "       최종 응답: ${STATUS_RESP}"
    exit 1
  fi

  sleep "$POLL_INTERVAL"
  elapsed=$((elapsed + POLL_INTERVAL))
done

# ── 3단계: 결과 ───────────────────────────────────────────────────────────────
echo ""
echo "=== [3/3] Smoke test 결과 ==="
echo "[PASS] status=CREATED  shipmentStatus=READY"
echo "       idemKey = ${IDEM_KEY}"
exit 0
