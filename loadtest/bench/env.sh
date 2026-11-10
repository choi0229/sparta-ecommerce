# 측정 스크립트 공통 설정. 직접 실행하지 않고 다른 스크립트에서 source 한다.
# 모든 경로는 저장소 기준이며, 환경 변수로 바꿀 수 있다.

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(cd "${BENCH_DIR}/.." && pwd)"
REPO_DIR="$(cd "${LOADTEST_DIR}/.." && pwd)"

RESULTS_DIR="${RESULTS_DIR:-${LOADTEST_DIR}/results}"
# 서비스 pid와 앱 로그. results 아래라 git 추적에서 제외된다
RUNTIME_DIR="${RUNTIME_DIR:-${RESULTS_DIR}/.runtime}"
COMPOSE_FILE="${COMPOSE_FILE:-${LOADTEST_DIR}/env/compose.yml}"
DC="docker compose -f ${COMPOSE_FILE}"

# verify.sh, outbox-watch.sh도 같은 변수를 읽는다
export ORDER_PSQL="${ORDER_PSQL:-${DC} exec -T order-db psql -U root -d orderdb}"
export INVENTORY_PSQL="${INVENTORY_PSQL:-${DC} exec -T inventory-db psql -U root -d inventorydb}"
export PRODUCT_PSQL="${PRODUCT_PSQL:-${DC} exec -T product-db psql -U root -d productdb}"
export KAFKA_GROUPS="${KAFKA_GROUPS:-${DC} exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092}"
KAFKA_TOPICS="${KAFKA_TOPICS:-${DC} exec -T kafka kafka-topics --bootstrap-server kafka:29092}"

ORDER_API_PORT="${ORDER_API_PORT:-8083}"
INVENTORY_API_PORT="${INVENTORY_API_PORT:-8082}"
PRODUCT_API_PORT="${PRODUCT_API_PORT:-8081}"
SKUS="${SKUS:-LOADTEST-SKU-A,LOADTEST-SKU-B}"

osql() { ${ORDER_PSQL} -At -F'|' -c "$1"; }
isql() { ${INVENTORY_PSQL} -At -F'|' -c "$1"; }
psqlp() { ${PRODUCT_PSQL} -At -F'|' -c "$1"; }
