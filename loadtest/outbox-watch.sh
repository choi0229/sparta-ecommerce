#!/usr/bin/env bash
# 부하 테스트 중 서비스별 Outbox 적체와 Kafka 컨슈머 지연을 주기적으로 CSV로 출력한다. 읽기 전용이다.
# 사용법: INTERVAL=5 ./outbox-watch.sh > ../results/rps10-outbox.csv   (Ctrl+C로 종료)
set -uo pipefail

INTERVAL="${INTERVAL:-5}"
ORDER_PSQL="${ORDER_PSQL:-docker compose exec -T order-db psql -U root -d orderdb}"
INVENTORY_PSQL="${INVENTORY_PSQL:-docker compose exec -T inventory-db psql -U root -d inventorydb}"
PRODUCT_PSQL="${PRODUCT_PSQL:-docker compose exec -T product-db psql -U root -d productdb}"
KAFKA_GROUPS="${KAFKA_GROUPS:-docker compose exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092}"

QUERY="SELECT count(*) FILTER (WHERE status = 'PENDING') || ',' || count(*) FILTER (WHERE status = 'PROCESSING') || ',' || count(*) FILTER (WHERE status = 'SENT') || ',' || count(*) FILTER (WHERE status = 'FAILED') FROM outbox_event"

lag() {
  ${KAFKA_GROUPS} --describe --group "$1" 2>/dev/null | awk '$6 ~ /^[0-9]+$/ {sum += $6} END {print sum + 0}'
}

echo "time,service,pending,processing,sent,failed,consumer_lag"
while true; do
  now=$(date +%H:%M:%S)
  echo "${now},order-api,$(${ORDER_PSQL} -At -c "${QUERY}"),$(lag order-api)"
  echo "${now},inventory-api,$(${INVENTORY_PSQL} -At -c "${QUERY}"),$(lag inventory-api)"
  echo "${now},product-api,$(${PRODUCT_PSQL} -At -c "${QUERY}"),$(lag product-api)"
  sleep "${INTERVAL}"
done
