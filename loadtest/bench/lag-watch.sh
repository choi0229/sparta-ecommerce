#!/usr/bin/env bash
# 컨슈머 그룹·토픽별 Consumer Lag을 주기적으로 CSV로 기록한다. 읽기 전용이다. (종료: kill)
# 사용법: INTERVAL=2 lag-watch.sh > consumer-lag.csv
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
INTERVAL="${INTERVAL:-5}"
echo "time,group,topic,current_offset,log_end_offset,lag"
while true; do
  now=$(date +%H:%M:%S)
  ${KAFKA_GROUPS} --describe --all-groups 2>/dev/null \
    | awk -v t="${now}" '$1 != "GROUP" && $6 ~ /^[0-9]+$/ {print t "," $1 "," $2 "," $4 "," $5 "," $6}'
  sleep "${INTERVAL}"
done
