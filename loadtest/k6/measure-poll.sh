#!/usr/bin/env bash
# 주문 상태 폴링 측정: 워밍업 1분 → pg_stat_statements 초기화 → CPU 기록 + 본 측정 5분 → DB 통계
# usage:
#   bash measure-poll.sh <label> <run번호> [RATE]   # RATE 기본값 100
#   bash measure-poll.sh <label> idle               # k6 없이 5분간 CPU만 기록
#
# CPU 수집:
#   db  = docker stats CPUPerc (코어 1개 = 100%)
#   app = actuator/prometheus process_cpu_usage × 100 (전체 코어 합 = 100%)
set -uo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage:"
  echo "  bash measure-poll.sh <label> <run번호> [RATE]   # RATE 기본값 100"
  echo "  bash measure-poll.sh <label> idle               # k6 없이 5분간 CPU만 기록"
  exit 1
fi

LABEL="$1"
N="$2"
RATE="${3:-100}"
BASE_URL="${BASE_URL:-http://localhost:8083}"
IDLE_SECONDS=300

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
if [[ "${N}" == "idle" ]]; then
  OUT="${ROOT}/loadtest/results/${LABEL}/idle"
else
  OUT="${ROOT}/loadtest/results/${LABEL}/rate-${RATE}/run-${N}"
fi

if ! grep -q '^process_cpu_usage' <<< "$(curl -sf "${BASE_URL}/actuator/prometheus")"; then
  echo "${BASE_URL}/actuator/prometheus 에서 process_cpu_usage를 읽을 수 없습니다."
  exit 1
fi

cd "${ROOT}"
DB_CONTAINER="$(docker compose ps -q order-db)"
if [[ -z "${DB_CONTAINER}" ]]; then
  echo "order-db 컨테이너를 찾을 수 없습니다."
  exit 1
fi

if [[ -e "${OUT}" ]]; then
  echo "${OUT} 가 이미 존재합니다."
  exit 1
fi
mkdir -p "${OUT}"

echo "[${OUT#${ROOT}/}] start"

start_cpu_log() {
  (
    while true; do
      db=$(docker stats --no-stream --format '{{.CPUPerc}}' "${DB_CONTAINER}")
      app=$(curl -s "${BASE_URL}/actuator/prometheus" \
        | awk '/^process_cpu_usage/ {printf "%.2f%%", $NF * 100}')
      echo "$(date '+%H:%M:%S') db=${db} app=${app}"
      sleep 5
    done
  ) > "${OUT}/cpu.log" 2>&1 &
  CPU_PID=$!
}

stop_cpu_log() {
  kill "${CPU_PID}"
  wait "${CPU_PID}" 2>/dev/null
}

# idle 모드: CPU만 기록
if [[ "${N}" == "idle" ]]; then
  start_cpu_log
  echo "[idle] start $(date '+%H:%M:%S')"
  sleep "${IDLE_SECONDS}"
  echo "[idle] end $(date '+%H:%M:%S')"
  stop_cpu_log
  echo "[done] ${OUT}"
  exit 0
fi

# 1) 워밍업 (결과 저장 안 함, 요약만 출력)
WARMUP_LOG="$(mktemp)"
echo "[warmup] start $(date '+%H:%M:%S')"
(cd "${SCRIPT_DIR}" && BASE_URL="${BASE_URL}" RATE="${RATE}" DURATION=1m bash run.sh poll) > "${WARMUP_LOG}" 2>&1
echo "[warmup] exit=$?"
grep -E 'dropped_iterations|checks_succeeded|http_req_failed\.|http_reqs' "${WARMUP_LOG}"
rm -f "${WARMUP_LOG}"

# 2) 통계 초기화
docker compose exec -T order-db psql -U root -d orderdb -c "SELECT pg_stat_statements_reset();" > /dev/null

# 3) CPU 기록 (5초 간격)
start_cpu_log

# 4) 본 측정
echo "[main] start $(date '+%H:%M:%S')"
(cd "${SCRIPT_DIR}" && BASE_URL="${BASE_URL}" RATE="${RATE}" DURATION=5m bash run.sh poll) > "${OUT}/k6.log" 2>&1
echo "[main] exit=$? end $(date '+%H:%M:%S')"

# 5) CPU 기록 중지
stop_cpu_log

# 6) DB 통계
docker compose exec -T order-db psql -U root -d orderdb < loadtest/sql/polling-query-stats.sql > "${OUT}/db.log" 2>&1

echo "[done] ${OUT}"
