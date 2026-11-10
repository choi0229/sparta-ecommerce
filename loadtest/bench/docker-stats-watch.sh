#!/usr/bin/env bash
# 격리 환경 컨테이너의 CPU·메모리(docker stats)와 서비스 JVM 프로세스의 CPU·RSS(ps)를 주기적으로 CSV로 기록한다.
# 읽기 전용이다. (종료: kill) macOS ps의 %cpu는 감쇠 평균이라 참고값이다.
# 사용법: INTERVAL=5 docker-stats-watch.sh > docker-stats.csv
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
INTERVAL="${INTERVAL:-5}"
PROJECT=$(${DC} config 2>/dev/null | awk '/^name:/ {print $2; exit}')
echo "time,kind,name,cpu_pct,mem"
while true; do
  now=$(date +%H:%M:%S)
  ids=$(docker ps -q --filter "label=com.docker.compose.project=${PROJECT}")
  [[ -n "${ids}" ]] && docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' ${ids} 2>/dev/null \
    | sed "s/^/${now},container,/"
  for service in order inventory product; do
    pid=$(cat "${RUNTIME_DIR}/${service}.pid" 2>/dev/null) || continue
    ps -o %cpu=,rss= -p "${pid}" 2>/dev/null \
      | awk -v t="${now}" -v s="${service}-api" '{printf "%s,jvm,%s,%s,%.0fMiB\n", t, s, $1, $2/1024}'
  done
  sleep "${INTERVAL}"
done
