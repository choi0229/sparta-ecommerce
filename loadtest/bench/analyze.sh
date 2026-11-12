#!/usr/bin/env bash
# measure.sh가 끝난 직후, 격리 환경이 살아 있을 때 실행한다. DB에서 필요한 값을 결과 디렉터리에 저장하고 analyze.py로 요약한다.
# DB 조회는 모두 읽기 전용이다. 저장된 결과만 다시 요약할 때는 python3 analyze.py <결과 디렉터리>를 쓴다.
# 사용법: analyze.sh <RUN_ID>
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

RUN_ID="${1:?usage: analyze.sh <RUN_ID>}"
D="${RESULTS_DIR}/${RUN_ID}"
[[ -f "${D}/load-start-utc.txt" ]] || { echo "측정 결과가 없습니다: ${D}" >&2; exit 2; }
SQL="${LOADTEST_DIR}/sql"
START=$(cat "${D}/load-start-utc.txt")
# 이번 실행의 마지막 주문 완료 5초 뒤까지를 분석 구간으로 본다
UNTIL=$(osql "select to_char((max(o.updated_at) + interval '5 second') at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from orders o join idempotency_request r on r.order_id=o.id where r.idem_key like '${RUN_ID}-%'")
echo "window since=${START} until=${UNTIL}" > "${D}/analysis-window.txt"

${ORDER_PSQL} -q -X -v run_id="${RUN_ID}" < "${SQL}/server-e2e.sql" > "${D}/server-e2e-result.txt"
: > "${D}/outbox-delay-result.txt"
for s in order inventory product; do
  v="$(echo "${s}" | tr '[:lower:]' '[:upper:]')_PSQL"
  P="${!v}"
  echo "== ${s}-api" >> "${D}/outbox-delay-result.txt"
  ${P} -q -X -v since="'${START}'" -v until="'${UNTIL}'" < "${SQL}/outbox-delay.sql" >> "${D}/outbox-delay-result.txt"
  ${P} -q -X -At -F'|' -v since="'${START}'" < "${SQL}/outbox-throughput.sql" > "${D}/outbox-throughput-${s}.txt"
  # claimed_at: 같은 값을 가진 행이 한 번의 claim(폴러 배치 1회 또는 즉시 발행 1건)이다
  cols="id, event_type, aggregate_id, extract(epoch from created_at), extract(epoch from sent_at), extract(epoch from claimed_at)"
  ${P} -q -X -c "copy (select ${cols} from outbox_event where created_at >= '${START}' and created_at < '${UNTIL}' order by id) to stdout with csv" > "${D}/outbox-rows-${s}.csv"
done

${KAFKA_TOPICS} --describe 2>/dev/null \
  | awk '/PartitionCount/ {for (i = 1; i <= NF; i++) {if ($i == "Topic:") t = $(i + 1); if ($i == "PartitionCount:") p = $(i + 1)} print t "=" p}' \
  | sort > "${D}/topic-partitions.txt"
for s in order inventory product; do
  log="${RUNTIME_DIR}/${s}-api.log"
  listeners=$(grep -oE 'ntainer#[0-9]+-[0-9]+-C' "${log}" | sort -u | wc -l | tr -d ' ')
  indexes=$(grep -oE 'ntainer#[0-9]+-[0-9]+-C' "${log}" | sed -E 's/ntainer#[0-9]+-([0-9]+)-C/\1/' | sort -u | tr '\n' ' ')
  echo "${s}: listeners=${listeners} concurrency_index=${indexes}(스레드명 #<리스너>-<동시성 인덱스>)"
done > "${D}/consumer-concurrency.txt"

python3 "${BENCH_DIR}/analyze.py" "${D}" > "${D}/summary.txt"
cat "${D}/summary.txt"
