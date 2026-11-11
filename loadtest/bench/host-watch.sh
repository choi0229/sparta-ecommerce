#!/usr/bin/env bash
# k6 프로세스 CPU(누적 CPU 시간 차분, 1코어=100%)와 호스트 전체 CPU 사용률(top)을 기록한다.
# 사용법: loadtest/bench/host-watch.sh > loadtest/results/<RUN_ID>/host-k6-cpu.csv  (측정 중 백그라운드 실행, INTERVAL 초 간격)
# 출력: time,k6_cpu_pct,k6_rss_kb,host_user_pct,host_sys_pct,host_idle_pct
INTERVAL="${INTERVAL:-2}"
echo "time,k6_cpu_pct,k6_rss_kb,host_user_pct,host_sys_pct,host_idle_pct"
to_s() { awk -F'[:.]' '{ if (NF==3) print $1*60+$2+$3/100; else if (NF==4) print $1*3600+$2*60+$3+$4/100; else print 0 }'; }
prev_t=""; prev_c=""
while true; do
  pid=$(pgrep -x k6 | head -1)
  now=$(python3 -c 'import time;print(time.time())')
  cpu=""; rss=""
  if [[ -n "${pid}" ]]; then
    c=$(ps -o time= -p "${pid}" | tr -d ' ' | to_s); rss=$(ps -o rss= -p "${pid}" | tr -d ' ')
    if [[ -n "${prev_c}" ]]; then cpu=$(python3 -c "print(round(max(0,(${c}-${prev_c}))/(${now}-${prev_t})*100,1))"); fi
    prev_c="${c}"; prev_t="${now}"
  else prev_c=""; fi
  host=$(top -l 2 -n 0 -s 1 | grep "CPU usage" | tail -1 | sed -E 's/.*: ([0-9.]+)% user, ([0-9.]+)% sys, ([0-9.]+)% idle.*/\1,\2,\3/')
  echo "$(date +%H:%M:%S),${cpu},${rss},${host}"
  sleep "$((INTERVAL > 1 ? INTERVAL - 1 : 0))"
done
