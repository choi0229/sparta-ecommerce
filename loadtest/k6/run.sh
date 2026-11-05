#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ORDER_PATH="${ORDER_PATH:-/orders}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS="${SCRIPT_DIR}/scripts"
DATA="${SCRIPT_DIR}/data"

echo "BASE_URL=${BASE_URL}"
echo "ORDER_PATH=${ORDER_PATH}"
echo

case "${1:-}" in
  create)
    echo "[RUN] create-order"
    k6 run -e BASE_URL="${BASE_URL}" -e ORDER_PATH="${ORDER_PATH}" \
      "${SCRIPTS}/create-order.js"
    ;;
  hot)
    HOT_SKU="${HOT_SKU:-SKU-001}"
    echo "[RUN] hot-sku (HOT_SKU=${HOT_SKU})"
    k6 run -e BASE_URL="${BASE_URL}" -e ORDER_PATH="${ORDER_PATH}" \
      -e HOT_SKU="${HOT_SKU}" \
      "${SCRIPTS}/hot-sku.js"
    ;;
  dist)
    SKU_CSV="${SKU_CSV:-${DATA}/skus.csv}"
    ITEMS_PER_ORDER="${ITEMS_PER_ORDER:-2}"
    echo "[RUN] distributed-sku (SKU_CSV=${SKU_CSV}, ITEMS_PER_ORDER=${ITEMS_PER_ORDER})"
    k6 run -e BASE_URL="${BASE_URL}" -e ORDER_PATH="${ORDER_PATH}" \
      -e SKU_CSV="${SKU_CSV}" -e ITEMS_PER_ORDER="${ITEMS_PER_ORDER}" \
      "${SCRIPTS}/distributed-sku.js"
    ;;
  e2e)
    # 사용법: RUN_ID=run1 PROFILE=constant RATE=10 DURATION=2m ./run.sh e2e
    RUN_ID="${RUN_ID:-run$(date +%Y%m%d%H%M%S)}"
    RESULTS="${SCRIPT_DIR}/../results"
    mkdir -p "${RESULTS}"
    echo "[RUN] create-order-e2e (RUN_ID=${RUN_ID}, PROFILE=${PROFILE:-ramp}, RATE=${RATE:-10})"
    k6 run -e BASE_URL="${E2E_BASE_URL:-http://localhost:8083/api}" -e RUN_ID="${RUN_ID}" \
      -e PROFILE="${PROFILE:-ramp}" -e RATE="${RATE:-10}" -e DURATION="${DURATION:-2m}" \
      -e SKUS="${SKUS:-LOADTEST-SKU-A,LOADTEST-SKU-B}" -e QUANTITY="${QUANTITY:-1}" \
      -e DUPLICATE_SEND="${DUPLICATE_SEND:-false}" \
      --summary-export "${RESULTS}/${RUN_ID}-summary.json" \
      "${SCRIPTS}/create-order-e2e.js"
    ;;
  *)
    echo "Usage:"
    echo "  ./run.sh create      # 기본 주문 부하"
    echo "  ./run.sh hot         # 핫 SKU 경합"
    echo "  ./run.sh dist        # 분산 SKU (CSV)"
    echo "  ./run.sh e2e         # 주문 접수부터 최종 상태까지 E2E"
    echo
    echo "Env examples:"
    echo "  BASE_URL=http://localhost:8080 ./run.sh create"
    echo "  HOT_SKU=SKU-0001 ./run.sh hot"
    echo "  SKU_CSV=./data/skus.csv ITEMS_PER_ORDER=2 ./run.sh dist"
    echo "  RUN_ID=run1 PROFILE=constant RATE=10 DURATION=2m ./run.sh e2e"
    exit 1
    ;;
esac