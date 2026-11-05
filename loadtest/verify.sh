#!/usr/bin/env bash
# 주문 DB와 재고 DB의 정합성 검증 결과를 합쳐 판정한다. 읽기 전용이다.
# 사용법: ./verify.sh <run_id>
# 종료 코드: 0 전체 통과, 1 정합성 위반(FAIL), 2 아직 처리 중(WAIT). 2이면 잠시 후 다시 실행한다.
set -euo pipefail

RUN_ID="${1:?usage: ./verify.sh <run_id>}"
SQL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sql"
ORDER_PSQL="${ORDER_PSQL:-docker compose exec -T order-db psql -U root -d orderdb}"
INVENTORY_PSQL="${INVENTORY_PSQL:-docker compose exec -T inventory-db psql -U root -d inventorydb}"

ORDER_RESULT=$(${ORDER_PSQL} -q -X -v run_id="${RUN_ID}" < "${SQL_DIR}/order-verify.sql")
ORDER_CSV=$(${ORDER_PSQL} -q -X -v run_id="${RUN_ID}" < "${SQL_DIR}/order-export.sql")

INVENTORY_RESULT=$({
  echo "CREATE TEMP TABLE run_orders (order_id BIGINT PRIMARY KEY, status TEXT);"
  echo "COPY run_orders FROM STDIN WITH (FORMAT csv);"
  [[ -n "${ORDER_CSV}" ]] && echo "${ORDER_CSV}"
  echo '\.'
  cat "${SQL_DIR}/inventory-verify.sql"
} | ${INVENTORY_PSQL} -q -X -v run_id="${RUN_ID}")

RESULT=$(printf '%s\n%s\n' "${ORDER_RESULT}" "${INVENTORY_RESULT}" | grep -E '\|(PASS|FAIL|WAIT|INFO)\|')

echo "=== 정합성 검증: run_id=${RUN_ID} ==="
echo "${RESULT}" | awk -F'|' '{printf "%-5s %-45s %s\n", $2, $1, $3}'

FAILS=$(echo "${RESULT}" | grep -c '|FAIL|' || true)
WAITS=$(echo "${RESULT}" | grep -c '|WAIT|' || true)
PASSES=$(echo "${RESULT}" | grep -c '|PASS|' || true)
echo "--- PASS=${PASSES} FAIL=${FAILS} WAIT=${WAITS}"

if [[ "${FAILS}" -gt 0 ]]; then
  echo "[FAIL] 정합성 위반이 있습니다."
  exit 1
fi
if [[ "${WAITS}" -gt 0 ]]; then
  echo "[WAIT] 아직 처리 중인 건이 있습니다. 잠시 후 다시 실행하세요."
  exit 2
fi
echo "[PASS] 정합성 검증 통과"
