#!/usr/bin/env bash
# Phase 8 Part A 무인 실행
#   1) 500 RPS 3분 사전 워밍업 (결과 저장 안 함)
#   2) label=redis: idle 1회, RATE 100/300/500 각 3회
#   3) 장애 (RATE 100, 1회씩): redis-pause-full, redis-stop-full, redis-pause-mid, redis-stop-mid
# 각 회차 후 멈춤 조건(dropped_iterations 발생, http_req_failed > 0, checks < 100%)을 검사하고,
# 걸리면 이유를 로그에 남기고 멈춘다. 어떤 경우든 종료 시 Redis를 복구한다.
#
# usage: caffeinate -dims bash loadtest/k6/run-phase8-a.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MEASURE="${SCRIPT_DIR}/measure-poll.sh"
RESULTS="${ROOT}/loadtest/results"
LOG="${RESULTS}/phase8-a.log"
BASE_URL="${BASE_URL:-http://localhost:8083}"

mkdir -p "${RESULTS}"
exec >> "${LOG}" 2>&1

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*"
}

stamp() {
  while IFS= read -r line; do
    echo "$(date '+%Y-%m-%d %H:%M:%S')   ${line}"
  done
}

cd "${ROOT}"

restore_redis() {
  docker compose unpause redis > /dev/null 2>&1
  docker compose start redis > /dev/null 2>&1
}

wait_redis_healthy() {
  local cid st
  cid="$(docker compose ps -a -q redis)"
  for _ in $(seq 1 60); do
    st="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null)"
    [[ "${st}" == "healthy" ]] && return 0
    sleep 1
  done
  return 1
}

on_exit() {
  local code=$?
  restore_redis
  if wait_redis_healthy; then
    log "[exit] code=${code}, redis healthy"
  else
    log "[exit] code=${code}, redis NOT healthy after restore"
  fi
}
trap on_exit EXIT

stop_run() {
  log "STOP: $*"
  exit 2
}

# k6.log 멈춤 조건 검사
check_k6_log() {
  local k6log="$1"
  [[ -f "${k6log}" ]] || stop_run "k6.log 없음: ${k6log}"
  if grep -q 'dropped_iterations' "${k6log}"; then
    stop_run "dropped_iterations 발생: $(grep 'dropped_iterations' "${k6log}" | tail -1 | tr -s ' ')"
  fi
  local failed checks
  failed="$(grep -E 'http_req_failed\.' "${k6log}" | tail -1 | tr -s ' ')"
  checks="$(grep -E 'checks_failed' "${k6log}" | tail -1 | tr -s ' ')"
  [[ "${failed}" == *" 0 out of "* ]] || stop_run "http_req_failed > 0 또는 요약 없음: ${failed:-<none>}"
  [[ "${checks}" == *" 0 out of "* ]] || stop_run "checks < 100% 또는 요약 없음: ${checks:-<none>}"
  log "[check] ok | ${failed} | ${checks}"
}

# measure-poll.sh 1회 실행 + 검사. usage: run_measure <label> <run> <rate> [ENV=...]
run_measure() {
  local label="$1" n="$2" rate="$3"
  shift 3
  log "[run] ${label} run-${n} rate=${rate} $*"
  env "$@" bash "${MEASURE}" "${label}" "${n}" "${rate}" 2>&1 | stamp
  local code=${PIPESTATUS[0]}
  [[ "${code}" == 0 ]] || stop_run "measure-poll.sh exit=${code} (${label} run-${n} rate=${rate})"
  check_k6_log "${RESULTS}/${label}/rate-${rate}/run-${n}/k6.log"
  wait_redis_healthy || stop_run "회차 후 redis not healthy (${label} run-${n})"
}

# 0) 사전 점검
log "===== Phase 8 Part A start (commit $(git rev-parse --short HEAD)) ====="
curl -sf -o /dev/null "${BASE_URL}/api/orders/status/seed-000001" || stop_run "앱 응답 없음: ${BASE_URL}"
wait_redis_healthy || stop_run "시작 시 redis not healthy"
for d in redis redis-pause-full redis-stop-full redis-pause-mid redis-stop-mid; do
  [[ -e "${RESULTS}/${d}" ]] && stop_run "결과 폴더가 이미 있음: ${RESULTS}/${d}"
done
log "[precheck] ok"

# 1) 사전 워밍업
log "[prewarm] 500 RPS 3m start"
PREWARM_LOG="$(mktemp)"
(cd "${SCRIPT_DIR}" && BASE_URL="${BASE_URL}" RATE=500 DURATION=3m bash run.sh poll) > "${PREWARM_LOG}" 2>&1
log "[prewarm] exit=$?"
grep -E 'dropped_iterations|checks_succeeded|http_req_failed\.|http_reqs' "${PREWARM_LOG}" | stamp
rm -f "${PREWARM_LOG}"

# 2) label=redis: idle + RATE별 3회
log "[run] redis idle"
bash "${MEASURE}" redis idle 2>&1 | stamp
[[ "${PIPESTATUS[0]}" == 0 ]] || stop_run "idle 실패"

for rate in 100 300 500; do
  for n in 1 2 3; do
    run_measure redis "${n}" "${rate}"
  done
done

# 3) 장애 시나리오 (RATE 100, 1회씩)
run_measure redis-pause-full 1 100 FAULT=pause FAULT_AT=0  FAULT_FOR=300
run_measure redis-stop-full  1 100 FAULT=stop  FAULT_AT=0  FAULT_FOR=300
run_measure redis-pause-mid  1 100 FAULT=pause FAULT_AT=60 FAULT_FOR=120
run_measure redis-stop-mid   1 100 FAULT=stop  FAULT_AT=60 FAULT_FOR=120

log "===== ALL DONE ====="
