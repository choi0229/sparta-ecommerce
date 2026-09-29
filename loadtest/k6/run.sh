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
  poll)
    RATE="${RATE:-100}"
    DURATION="${DURATION:-5m}"
    SEED_COUNT="${SEED_COUNT:-10000}"
    echo "[RUN] order-status-poll (RATE=${RATE}/s, DURATION=${DURATION}, SEED_COUNT=${SEED_COUNT})"
    k6 run -e BASE_URL="${BASE_URL}" -e RATE="${RATE}" \
      -e DURATION="${DURATION}" -e SEED_COUNT="${SEED_COUNT}" \
      "${SCRIPTS}/order-status-poll.js"
    ;;
  *)
    echo "Usage:"
    echo "  ./run.sh create      # 기본 주문 부하"
    echo "  ./run.sh hot         # 핫 SKU 경합"
    echo "  ./run.sh dist        # 분산 SKU (CSV)"
    echo "  ./run.sh poll        # 주문 상태 폴링 (RATE, DURATION, SEED_COUNT)"
    echo
    echo "Env examples:"
    echo "  BASE_URL=http://localhost:8080 ./run.sh create"
    echo "  HOT_SKU=SKU-0001 ./run.sh hot"
    echo "  SKU_CSV=./data/skus.csv ITEMS_PER_ORDER=2 ./run.sh dist"
    exit 1
    ;;
esac