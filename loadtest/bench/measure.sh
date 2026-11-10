#!/usr/bin/env bash
# 고정 부하 한 레벨을 측정한다: 사전 조건 확인 → 재고 스냅샷 → 수집기 시작 → k6 → 완료 대기 → 정합성 검증.
# 사용법: measure.sh <RUN_ID> <RATE> [DURATION=2m]
# 결과: loadtest/results/<RUN_ID>/  분석은 이어서 analyze.sh <RUN_ID>
# 종료 코드: 0 통과, 1 정합성 FAIL, 2 verify WAIT, 3 사전 조건 미충족, 4 제한 시간 안에 완료되지 않음
#
# 환경 변수: DRAIN_TIMEOUT_S(부하 종료 후 완료 대기 상한, 기본 600), WATCH_INTERVAL(기본 2),
#            STATS_INTERVAL(docker stats 간격, 기본 5), MAX_VUS / PRE_ALLOCATED_VUS(k6에 그대로 전달)
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

RUN_ID="${1:?usage: measure.sh <RUN_ID> <RATE> [DURATION]}"
RATE="${2:?usage: measure.sh <RUN_ID> <RATE> [DURATION]}"
DURATION="${3:-2m}"
DRAIN_TIMEOUT_S="${DRAIN_TIMEOUT_S:-600}"
WATCH_INTERVAL="${WATCH_INTERVAL:-2}"
STATS_INTERVAL="${STATS_INTERVAL:-5}"

case "${DURATION}" in
  *m) DURATION_S=$(( ${DURATION%m} * 60 )) ;;
  *s) DURATION_S=${DURATION%s} ;;
  *) echo "DURATION must end with m or s: ${DURATION}" >&2; exit 2 ;;
esac

D="${RESULTS_DIR}/${RUN_ID}"
[[ -e "${D}" ]] && { echo "결과 디렉터리가 이미 있습니다: ${D} (새 RUN_ID를 쓰세요)" >&2; exit 2; }
mkdir -p "${D}/logs"
now_epoch() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
lag_total() { ${KAFKA_GROUPS} --describe --all-groups 2>/dev/null | awk '$1 != "GROUP" && $6 ~ /^[0-9]+$/ {s += $6} END {print s + 0}'; }
db_stats() {
  for s in order inventory product; do
    local v; v="$(echo "${s}" | tr '[:lower:]' '[:upper:]')_PSQL"
    ${!v} -q -X -At -F'|' < "${LOADTEST_DIR}/sql/db-stats.sql" | sed "s/^/${s}|/"
  done
}

echo "== 사전 확인"
{
  echo "reserved_reservations=$(isql "select count(*) from inventory_reservation where status='RESERVED'")"
  echo "pending_requests=$(osql "select count(*) from idempotency_request where status='PENDING'")"
  echo "in_progress_orders=$(osql "select count(*) from orders where status not in ('COMPLETED','FAILED','EXPIRED','CANCELED')")"
  echo "outbox_unsent_order=$(osql "select count(*) from outbox_event where status<>'SENT'")"
  echo "outbox_unsent_inventory=$(isql "select count(*) from outbox_event where status<>'SENT'")"
  echo "outbox_unsent_product=$(psqlp "select count(*) from outbox_event where status<>'SENT'")"
  echo "consumer_lag_total=$(lag_total)"
} | tee "${D}/precheck.txt"
if grep -qvE '=0$' "${D}/precheck.txt"; then
  echo "사전 조건이 0이 아닙니다. 측정하지 않습니다." | tee -a "${D}/precheck.txt"
  exit 3
fi

cat "${RUNTIME_DIR}"/*.args > "${D}/service-config.txt" 2>/dev/null
echo "rate=${RATE} duration=${DURATION}" >> "${D}/service-config.txt"
echo "${DURATION_S}" > "${D}/load-duration-s.txt"
${INVENTORY_PSQL} -q -X -v run_id="${RUN_ID}" -v skus="${SKUS}" < "${LOADTEST_DIR}/sql/before.sql" > "${D}/before-snapshot.txt"
cat "${D}/before-snapshot.txt"
for s in order inventory product; do
  wc -l < "${RUNTIME_DIR}/${s}-api.log" | tr -d ' ' > "${D}/logs/.${s}.start"
done
db_stats > "${D}/db-stats-before.txt"

INTERVAL="${WATCH_INTERVAL}" "${LOADTEST_DIR}/outbox-watch.sh" > "${D}/outbox-watch.csv" 2>/dev/null & OW=$!
INTERVAL="${WATCH_INTERVAL}" "${BENCH_DIR}/lag-watch.sh" > "${D}/consumer-lag.csv" 2>/dev/null & LW=$!
INTERVAL="${WATCH_INTERVAL}" "${BENCH_DIR}/jvm-metrics-watch.sh" > "${D}/jvm-metrics.csv" 2>/dev/null & MW=$!
INTERVAL="${STATS_INTERVAL}" "${BENCH_DIR}/docker-stats-watch.sh" > "${D}/docker-stats.csv" 2>/dev/null & SW=$!
trap 'kill ${OW} ${LW} ${MW} ${SW} 2>/dev/null' EXIT
sleep 4

T0=$(now_epoch)
python3 -c "import datetime; print(datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ'))" > "${D}/load-start-utc.txt"
echo "== k6 시작 $(cat "${D}/load-start-utc.txt") RATE=${RATE} DURATION=${DURATION}"
k6 run -e BASE_URL="http://localhost:${ORDER_API_PORT}/api" -e RUN_ID="${RUN_ID}" -e PROFILE=constant \
  -e RATE="${RATE}" -e DURATION="${DURATION}" -e SKUS="${SKUS}" \
  --summary-export "${D}/k6-summary.json" "${LOADTEST_DIR}/k6/scripts/create-order-e2e.js" > "${D}/k6-stdout.txt" 2>&1
K6_EXIT=$?
T1=$(now_epoch)
echo "k6 exit=${K6_EXIT}, k6 실행 $(python3 -c "print(round(${T1} - ${T0}, 1))")초" | tee "${D}/k6-exit.txt"

echo "== 완료 대기 (최대 ${DRAIN_TIMEOUT_S}초)"
deadline=$(( $(date +%s) + DRAIN_TIMEOUT_S ))
while :; do
  p=$(osql "select count(*) from idempotency_request where idem_key like '${RUN_ID}-%' and status='PENDING'")
  o=$(osql "select count(*) from orders o join idempotency_request r on r.order_id=o.id where r.idem_key like '${RUN_ID}-%' and o.status not in ('COMPLETED','FAILED','EXPIRED','CANCELED')")
  [[ "${p}${o}" == "00" ]] && break
  [[ $(date +%s) -ge ${deadline} ]] && break
  sleep 1
done
echo "remaining pending=${p} in_progress=${o} (k6 종료 후 $(( $(date +%s) - ${T1%.*} ))초)" | tee "${D}/drain.txt"
if [[ "${p}${o}" != "00" ]]; then
  echo "-- 남은 주문 상태 분포" | tee -a "${D}/drain.txt"
  osql "select o.status, s.state, count(*) from orders o join idempotency_request r on r.order_id=o.id left join order_saga_state s on s.saga_id=o.saga_id where r.idem_key like '${RUN_ID}-%' group by 1,2 order by 1,2" | tee -a "${D}/drain.txt"
  osql "select status, count(*) from idempotency_request where idem_key like '${RUN_ID}-%' group by 1" | tee -a "${D}/drain.txt"
fi

sleep 6
kill ${OW} ${LW} ${MW} ${SW} 2>/dev/null
wait ${OW} ${LW} ${MW} ${SW} 2>/dev/null
trap - EXIT
db_stats > "${D}/db-stats-after.txt"
for s in order inventory product; do
  tail -n +$(( $(cat "${D}/logs/.${s}.start") + 1 )) "${RUNTIME_DIR}/${s}-api.log" > "${D}/logs/${s}-api-window.log"
  rm -f "${D}/logs/.${s}.start"
done

python3 - "${T0}" "${DURATION_S}" > "${D}/timing.txt" <<PY
import subprocess, sys
t0, duration = float(sys.argv[1]), float(sys.argv[2])
def q(sql):
    return float(subprocess.check_output(["bash", "-c", '. "${BENCH_DIR}/env.sh"; osql "%s"' % sql]).decode().strip())
run = "'${RUN_ID}-%'"
last = q("select extract(epoch from max(o.updated_at)) from orders o join idempotency_request r on r.order_id=o.id where r.idem_key like " + run)
first = q("select extract(epoch from min(created_at)) from idempotency_request where idem_key like " + run)
last_accept = q("select extract(epoch from max(created_at)) from idempotency_request where idem_key like " + run)
print(f"first_accept_offset_s={first - t0:.2f}")
print(f"last_accept_offset_s={last_accept - t0:.2f}")
print(f"arrival_end_offset_s={duration:.2f} (k6 start + DURATION)")
print(f"last_completion_offset_s={last - t0:.2f}")
print(f"drain_after_arrival_end_s={last - (t0 + duration):.2f}")
print(f"drain_after_last_accept_s={last - last_accept:.2f}")
PY
cat "${D}/timing.txt"
[[ "${p}${o}" != "00" ]] && { echo "제한 시간 안에 모든 주문이 끝나지 않았습니다."; exit 4; }

echo "== verify.sh"
"${LOADTEST_DIR}/verify.sh" "${RUN_ID}" > "${D}/verify.txt" 2>&1
VERIFY_EXIT=$?
echo "verify exit=${VERIFY_EXIT}" | tee -a "${D}/verify.txt"
tail -3 "${D}/verify.txt"
exit ${VERIFY_EXIT}
