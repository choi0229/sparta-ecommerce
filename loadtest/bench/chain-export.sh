#!/usr/bin/env bash
# 주문 단위 연결 정보를 저장한다(읽기 전용 조회). analyze.sh 직후, 격리 환경이 살아 있을 때 실행한다.
# 사용법: loadtest/bench/chain-export.sh <RUN_ID>  → loadtest/results/<RUN_ID>/orders-chain.csv
# orders-chain.csv: idem_key, 접수(epoch), order_id, 완료 시각(orders.updated_at epoch), status, requested 생성, requested 발행, requestId
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
RUN_ID="${1:?usage: chain-export.sh <RUN_ID>}"
D="${RESULTS_DIR}/${RUN_ID}"
${ORDER_PSQL} -q -X -c "copy (
  select r.idem_key, extract(epoch from r.created_at), o.id, extract(epoch from o.updated_at), o.status,
         extract(epoch from e.created_at), extract(epoch from e.sent_at), e.payload::jsonb->>'requestId'
  from idempotency_request r
  join orders o on o.id = r.order_id
  join outbox_event e on e.event_type = 'productSnapshot-requested-event' and e.payload::jsonb->>'idemKey' = r.idem_key
  where r.idem_key like '${RUN_ID}-%' order by r.created_at) to stdout with csv" > "${D}/orders-chain.csv"
wc -l < "${D}/orders-chain.csv"
