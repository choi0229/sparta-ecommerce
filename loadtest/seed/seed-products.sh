#!/usr/bin/env bash
# 부하 테스트용 상품과 재고를 실제 API 경로(상품 생성 → inventory-init-event → 재고 생성)로 만든다.
# 처음 한 번만 실행한다. 이미 SKU가 있으면 product-api가 거절한다. 재고 추가는 seed-stock.sql을 사용한다.
#
# 사용법: PRODUCT_API=http://localhost:8081 STOCK=20000 ./seed-products.sh
set -euo pipefail

PRODUCT_API="${PRODUCT_API:-http://localhost:8081}"
STOCK="${STOCK:-20000}"
SKU_A="${SKU_A:-LOADTEST-SKU-A}"
SKU_B="${SKU_B:-LOADTEST-SKU-B}"

curl -sf -X POST "${PRODUCT_API}/api/categories" -H 'Content-Type: application/json' \
  -d '{"name":"loadtest"}' > /dev/null

if [[ -z "${CATEGORY_ID:-}" ]]; then
  CATEGORY_ID=$(curl -sf "${PRODUCT_API}/api/categories" \
    | jq -r '[.. | objects | select(.name? == "loadtest") | .id] | first')
fi
if [[ -z "${CATEGORY_ID}" || "${CATEGORY_ID}" == "null" ]]; then
  echo "[FAIL] 카테고리 ID를 찾지 못했습니다. CATEGORY_ID를 직접 지정해 주세요." >&2
  exit 1
fi

curl -sf -X POST "${PRODUCT_API}/api/admin/products" -H 'Content-Type: application/json' -d @- <<JSON
{
  "name": "부하테스트 상품",
  "brandName": "loadtest",
  "categoryId": ${CATEGORY_ID},
  "description": "k6 E2E 부하 테스트용",
  "variants": [
    {"sku": "${SKU_A}", "price": 1000, "stockQuantity": ${STOCK}, "optionJson": {"option": "a"}},
    {"sku": "${SKU_B}", "price": 500, "stockQuantity": ${STOCK}, "optionJson": {"option": "b"}}
  ]
}
JSON
echo
echo "[OK] 요청 완료. 재고 행은 이벤트로 비동기 생성됩니다. inventory_stock에서 ${SKU_A}, ${SKU_B}를 확인하세요."
