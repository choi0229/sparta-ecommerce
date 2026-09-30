#!/usr/bin/env bash
# 주문 상태 폴링 측정: 워밍업 1분 → pg_stat_statements 초기화 → CPU 기록 + 본 측정 5분 → DB 통계
# usage:
#   bash measure-poll.sh <label> <run번호> [RATE]   # RATE 기본값 100
#   bash measure-poll.sh <label> idle               # k6 없이 5분간 CPU만 기록
#
# Redis 장애 주입 (선택, 본 측정 5분 중):
#   FAULT=stop|pause FAULT_AT=60 FAULT_FOR=60 bash measure-poll.sh <label> <run번호> [RATE]
#   본 측정 시작 FAULT_AT초 뒤 docker compose stop|pause redis, FAULT_FOR초 뒤 start|unpause.
#   스크립트가 어떻게 끝나든 종료 시 Redis를 복구한다.
#
# meta.txt: 본 측정 시작·종료, 장애 주입·복구 시각 (ISO 8601, 로컬 시간대)
# cache.log: 본 측정 직전·직후 캐시 카운터와 차이, hit ratio = hit/(hit+miss+error)
#
# 시험용 덮어쓰기: MAIN_SECONDS(기본 300), WARMUP_SECONDS(기본 60)
#
# CPU 수집:
#   db, redis = docker stats CPUPerc (코어 1개 = 100%), redis가 running이 아니면 redis=-
#   app = actuator/prometheus process_cpu_usage × 100 (전체 코어 합 = 100%)
set -uo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage:"
  echo "  bash measure-poll.sh <label> <run번호> [RATE]   # RATE 기본값 100"
  echo "  bash measure-poll.sh <label> idle               # k6 없이 5분간 CPU만 기록"
  echo "  FAULT=stop|pause FAULT_AT=60 FAULT_FOR=60 bash measure-poll.sh <label> <run번호> [RATE]"
  exit 1
fi

LABEL="$1"
N="$2"
RATE="${3:-100}"
BASE_URL="${BASE_URL:-http://localhost:8083}"
IDLE_SECONDS=300
MAIN_SECONDS="${MAIN_SECONDS:-300}"
WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
FAULT="${FAULT:-}"
FAULT_AT="${FAULT_AT:-60}"
FAULT_FOR="${FAULT_FOR:-60}"

if [[ -n "${FAULT}" ]]; then
  if [[ "${FAULT}" != "stop" && "${FAULT}" != "pause" ]]; then
    echo "FAULT는 stop 또는 pause만 가능합니다: ${FAULT}"
    exit 1
  fi
  if [[ "${2}" == "idle" ]]; then
    echo "idle 모드에서는 FAULT를 사용할 수 없습니다."
    exit 1
  fi
  if (( FAULT_AT + FAULT_FOR > MAIN_SECONDS )); then
    echo "FAULT_AT + FAULT_FOR 는 ${MAIN_SECONDS}초 이하여야 합니다."
    exit 1
  fi
fi

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

now_iso() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

meta() {
  echo "$1=$2" >> "${OUT}/meta.txt"
}

meta label "${LABEL}"
meta run "${N}"
[[ "${N}" != "idle" ]] && meta rate "${RATE}"
meta git_commit "$(git rev-parse --short HEAD)"
[[ -n "${FAULT}" ]] && { meta fault "${FAULT}"; meta fault_at_sec "${FAULT_AT}"; meta fault_for_sec "${FAULT_FOR}"; }

restore_redis() {
  docker compose unpause redis > /dev/null 2>&1
  docker compose start redis > /dev/null 2>&1
}

wait_redis_healthy() {
  local cid st
  cid="$(docker compose ps -q redis)"
  for _ in $(seq 1 60); do
    st="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null)"
    [[ "${st}" == "healthy" ]] && return 0
    sleep 1
  done
  return 1
}

inject_fault() {
  sleep "${FAULT_AT}"
  docker compose "${FAULT}" redis > /dev/null 2>&1
  meta fault_start "$(now_iso)"
  echo "[fault] ${FAULT} redis $(date '+%H:%M:%S')"
  sleep "${FAULT_FOR}"
  if [[ "${FAULT}" == "stop" ]]; then
    docker compose start redis > /dev/null 2>&1
  else
    docker compose unpause redis > /dev/null 2>&1
  fi
  meta fault_end "$(now_iso)"
  echo "[fault] recover redis $(date '+%H:%M:%S')"
  if wait_redis_healthy; then
    meta redis_healthy "$(now_iso)"
  else
    meta redis_healthy "not healthy within 60s"
  fi
}

on_exit() {
  [[ -n "${FAULT_PID:-}" ]] && kill "${FAULT_PID}" 2>/dev/null
  [[ -n "${FAULT}" ]] && restore_redis
}
trap on_exit EXIT

start_cpu_log() {
  (
    while true; do
      redis_cid="$(docker compose ps -a -q redis 2>/dev/null)"
      redis_state=""
      [[ -n "${redis_cid}" ]] && redis_state="$(docker inspect -f '{{.State.Status}}' "${redis_cid}" 2>/dev/null)"
      if [[ "${redis_state}" == "running" ]]; then
        stats="$(docker stats --no-stream --format '{{.Container}} {{.CPUPerc}}' "${DB_CONTAINER}" "${redis_cid}")"
        redis=$(awk -v c="${redis_cid}" '$1 == c {print $2}' <<< "${stats}")
      else
        stats="$(docker stats --no-stream --format '{{.Container}} {{.CPUPerc}}' "${DB_CONTAINER}")"
        redis="-"
      fi
      db=$(awk -v c="${DB_CONTAINER}" '$1 == c {print $2}' <<< "${stats}")
      app=$(curl -s "${BASE_URL}/actuator/prometheus" \
        | awk '/^process_cpu_usage/ {printf "%.2f%%", $NF * 100}')
      echo "$(date '+%H:%M:%S') db=${db} redis=${redis:--} app=${app}"
      sleep 5
    done
  ) > "${OUT}/cpu.log" 2>&1 &
  CPU_PID=$!
}

# 출력: hit miss error save evict
read_cache_counters() {
  curl -s "${BASE_URL}/actuator/prometheus" | awk '
    /^order_status_cache_requests_total\{/ {
      if ($0 ~ /result="hit"/) hit += $NF
      else if ($0 ~ /result="miss"/) miss += $NF
      else if ($0 ~ /result="error"/) err += $NF
    }
    /^order_status_cache_write_errors_total\{/ {
      if ($0 ~ /operation="save"/) save += $NF
      else if ($0 ~ /operation="evict"/) evict += $NF
    }
    END { printf "%d %d %d %d %d\n", hit, miss, err, save, evict }'
}

write_cache_log() {
  read -r h1 m1 e1 s1 v1 <<< "$1"
  read -r h2 m2 e2 s2 v2 <<< "$2"
  local dh=$((h2 - h1)) dm=$((m2 - m1)) de=$((e2 - e1))
  {
    echo "before hit=${h1} miss=${m1} error=${e1} write_error_save=${s1} write_error_evict=${v1}"
    echo "after  hit=${h2} miss=${m2} error=${e2} write_error_save=${s2} write_error_evict=${v2}"
    echo "delta  hit=${dh} miss=${dm} error=${de} write_error_save=$((s2 - s1)) write_error_evict=$((v2 - v1))"
    awk -v h="${dh}" -v m="${dm}" -v e="${de}" \
      'BEGIN { t = h + m + e; if (t > 0) printf "hit_ratio=%.4f\n", h / t; else print "hit_ratio=-" }'
  } > "${OUT}/cache.log"
}

stop_cpu_log() {
  kill "${CPU_PID}"
  wait "${CPU_PID}" 2>/dev/null
}

# idle 모드: CPU만 기록
if [[ "${N}" == "idle" ]]; then
  start_cpu_log
  meta idle_start "$(now_iso)"
  echo "[idle] start $(date '+%H:%M:%S')"
  sleep "${IDLE_SECONDS}"
  meta idle_end "$(now_iso)"
  echo "[idle] end $(date '+%H:%M:%S')"
  stop_cpu_log
  echo "[done] ${OUT}"
  exit 0
fi

# 1) 워밍업 (결과 저장 안 함, 요약만 출력)
WARMUP_LOG="$(mktemp)"
echo "[warmup] start $(date '+%H:%M:%S')"
(cd "${SCRIPT_DIR}" && BASE_URL="${BASE_URL}" RATE="${RATE}" DURATION="${WARMUP_SECONDS}s" bash run.sh poll) > "${WARMUP_LOG}" 2>&1
echo "[warmup] exit=$?"
grep -E 'dropped_iterations|checks_succeeded|http_req_failed\.|http_reqs' "${WARMUP_LOG}"
rm -f "${WARMUP_LOG}"

# 2) 통계 초기화
docker compose exec -T order-db psql -U root -d orderdb -c "SELECT pg_stat_statements_reset();" > /dev/null

# 3) CPU 기록 (5초 간격)
start_cpu_log

# 4) 본 측정 (FAULT 지정 시 장애 주입 병행)
CACHE_BEFORE="$(read_cache_counters)"
meta main_start "$(now_iso)"
echo "[main] start $(date '+%H:%M:%S')"
if [[ -n "${FAULT}" ]]; then
  inject_fault &
  FAULT_PID=$!
fi
(cd "${SCRIPT_DIR}" && BASE_URL="${BASE_URL}" RATE="${RATE}" DURATION="${MAIN_SECONDS}s" bash run.sh poll) > "${OUT}/k6.log" 2>&1
K6_EXIT=$?
meta main_end "$(now_iso)"
CACHE_AFTER="$(read_cache_counters)"
write_cache_log "${CACHE_BEFORE}" "${CACHE_AFTER}"
meta k6_exit "${K6_EXIT}"
echo "[main] exit=${K6_EXIT} end $(date '+%H:%M:%S')"
if [[ -n "${FAULT_PID:-}" ]]; then
  wait "${FAULT_PID}"
  FAULT_PID=""
fi

# 5) CPU 기록 중지
stop_cpu_log

# 6) DB 통계
docker compose exec -T order-db psql -U root -d orderdb < loadtest/sql/polling-query-stats.sql > "${OUT}/db.log" 2>&1

echo "[done] ${OUT}"
