#!/usr/bin/env bash
#
# e2e-order-shipment-smoke.sh
#
# 사전 조건:
#   - order-api    가 localhost:8083 에서 응답 중이어야 한다.
#   - logistics-api 가 localhost:8084 에서 응답 중이어야 한다.
#   - 두 서비스가 Kafka로 연결되어 있어야 한다.
#   - kubectl port-forward 또는 minikube service 로 포트가 열려 있어야 한다.
#
# 실행 방법:
#   chmod +x scripts/e2e-order-shipment-smoke.sh
#   bash scripts/e2e-order-shipment-smoke.sh
#
# 성공 조건 (1차):
#   status == CREATED  &&  shipmentStatus == READY
# 성공 조건 (2차):
#   status == CREATED  &&  shipmentStatus == SHIPPED
#
# 종료 코드:
#   0 = 성공
#   1 = 실패 (타임아웃 포함)

set -euo pipefail

ORDER_API="http://localhost:8083"
LOGISTICS_API="http://localhost:8084"
POLL_INTERVAL=3   # 초
# Kafka/Outbox 기반 비동기 처리라 self-hosted runner/minikube 환경에서 30초를 초과할 수 있음
MAX_WAIT_SECONDS=60
NAMESPACE="ecommerce"

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
    # "key":"value" 또는 "key":123 패턴 추출 (공백 허용)
    echo "$json" | grep -o "\"${key}\"[[:space:]]*:[[:space:]]*[^,}]*" \
                 | sed 's/.*:[[:space:]]*//' \
                 | tr -d '"'
  fi
}

poll_until() {
  local label="$1" target_status="$2" target_shipment="$3"
  local elapsed=0

  echo ""
  echo "=== ${label} — 폴링 시작 (최대 ${MAX_WAIT_SECONDS}초) ==="

  while true; do
    STATUS_RESP=$(curl -s "${ORDER_API}/api/orders/status/${IDEM_KEY}")
    STATUS=$(extract "$STATUS_RESP" "status")
    SHIPMENT=$(extract "$STATUS_RESP" "shipmentStatus")

    echo "[${elapsed}s] status=${STATUS:-null}  shipmentStatus=${SHIPMENT:-null}"

    if [[ "$STATUS" == "$target_status" && "$SHIPMENT" == "$target_shipment" ]]; then
      return 0
    fi

    if [[ $elapsed -ge $MAX_WAIT_SECONDS ]]; then
      echo ""
      echo "[FAIL] ${MAX_WAIT_SECONDS}초 안에 목표 상태(status=${target_status}, shipmentStatus=${target_shipment})에 도달하지 못했습니다."
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

    sleep "$POLL_INTERVAL"
    elapsed=$((elapsed + POLL_INTERVAL))
  done
}

# ── 1단계: 주문 생성 ───────────────────────────────────────────────────────────
echo ""
echo "=== [1/5] POST /api/orders — 주문 생성 ==="
CREATE_BODY='{"userId":1,"items":[{"sku":"SKU-TEST-001","quantity":1}],"shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울시 강남구 테헤란로 1"}}'

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

# ── 2단계: 1차 polling (status=CREATED, shipmentStatus=READY) ─────────────────
poll_until "[2/5] GET /api/orders/status/${IDEM_KEY} — 1차 목표: shipmentStatus=READY" \
  "CREATED" "READY"

echo "[OK] 1차 조건 달성: status=CREATED  shipmentStatus=READY"

# ── 3단계: orderId → shipmentId 조회 ──────────────────────────────────────────
echo ""
echo "=== [3/5] orderId 추출 및 shipmentId 조회 ==="

STATUS_RESP=$(curl -s "${ORDER_API}/api/orders/status/${IDEM_KEY}")
ORDER_ID=$(extract "$STATUS_RESP" "orderId")

if [[ -z "$ORDER_ID" ]]; then
  echo "[FAIL] orderId를 추출하지 못했습니다."
  echo "       응답: ${STATUS_RESP}"
  exit 1
fi

echo "[OK] orderId = ${ORDER_ID}"

SHIPMENT_RESP=$(curl -s "${LOGISTICS_API}/shipments/by-order/${ORDER_ID}")
SHIPMENT_ID=$(extract "$SHIPMENT_RESP" "id")

if [[ -z "$SHIPMENT_ID" ]]; then
  echo "[FAIL] shipmentId를 추출하지 못했습니다."
  echo "       응답: ${SHIPMENT_RESP}"
  exit 1
fi

echo "[OK] shipmentId = ${SHIPMENT_ID}"

# ── 4단계: 배송 상태 READY → SHIPPED 변경 ─────────────────────────────────────
echo ""
echo "=== [4/5] PATCH /shipments/${SHIPMENT_ID}/status — READY → SHIPPED ==="

UPDATE_RESP=$(curl -s -X PATCH "${LOGISTICS_API}/shipments/${SHIPMENT_ID}/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}')

echo "응답: ${UPDATE_RESP}"

UPDATE_STATUS=$(extract "$UPDATE_RESP" "status")
if [[ "$UPDATE_STATUS" != "SHIPPED" ]]; then
  echo "[FAIL] 배송 상태 변경 실패. 응답 status=${UPDATE_STATUS:-null}"
  exit 1
fi

echo "[OK] logistics-api shipment status = SHIPPED"

# ── 5단계: 2차 polling (status=CREATED, shipmentStatus=SHIPPED) ───────────────
poll_until "[5/5] GET /api/orders/status/${IDEM_KEY} — 2차 목표: shipmentStatus=SHIPPED" \
  "CREATED" "SHIPPED"

# ── 최종 결과 ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Smoke test 결과 ==="
echo "[PASS] 1차: status=CREATED  shipmentStatus=READY"
echo "[PASS] 2차: status=CREATED  shipmentStatus=SHIPPED"
echo "       idemKey    = ${IDEM_KEY}"
echo "       orderId    = ${ORDER_ID}"
echo "       shipmentId = ${SHIPMENT_ID}"
exit 0
