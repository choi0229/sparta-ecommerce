#!/usr/bin/env bash
# 서비스별 /actuator/prometheus를 주기적으로 읽어 CPU, 힙, GC, HikariCP, 스레드를 CSV로 기록한다. 읽기 전용이다. (종료: kill)
# process_cpu / system_cpu는 0~1 비율이며 전체 코어 대비 값이다. Hikari active/pending은 수집 순간의 값이다.
# 사용법: INTERVAL=2 jvm-metrics-watch.sh > jvm-metrics.csv
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
INTERVAL="${INTERVAL:-2}"
echo "time,service,process_cpu,system_cpu,heap_used_mb,heap_max_mb,gc_pause_count,gc_pause_sum_s,hikari_active,hikari_idle,hikari_pending,hikari_max,hikari_acquire_max_ms,hikari_timeouts,threads_live"
while true; do
  now=$(python3 -c 'import time; print(f"{time.time():.3f}")')
  for target in "order:${ORDER_API_PORT}" "inventory:${INVENTORY_API_PORT}" "product:${PRODUCT_API_PORT}"; do
    service=${target%%:*}
    port=${target##*:}
    curl -s -m 2 "localhost:${port}/actuator/prometheus" | awk -v t="${now}" -v s="${service}-api" '
      /^#/ {next}
      /^process_cpu_usage/ {pc=$NF}
      /^system_cpu_usage/ {sc=$NF}
      /^jvm_memory_used_bytes/ && /area="heap"/ {hu+=$NF}
      /^jvm_memory_max_bytes/ && /area="heap"/ && $NF>0 {hm+=$NF}
      /^jvm_gc_pause_seconds_count/ {gcc+=$NF}
      /^jvm_gc_pause_seconds_sum/ {gcs+=$NF}
      /^hikaricp_connections_active/ {ha=$NF}
      /^hikaricp_connections_idle/ {hi=$NF}
      /^hikaricp_connections_pending/ {hp=$NF}
      /^hikaricp_connections_max/ {hx=$NF}
      /^hikaricp_connections_acquire_seconds_max/ {aq=$NF*1000}
      /^hikaricp_connections_timeout_total/ {to=$NF}
      /^jvm_threads_live_threads/ {th=$NF}
      END {printf "%s,%s,%.4f,%.4f,%.1f,%.1f,%d,%.4f,%d,%d,%d,%d,%.1f,%d,%d\n", t,s,pc,sc,hu/1048576,hm/1048576,gcc,gcs,ha,hi,hp,hx,aq,to,th}'
  done
  sleep "${INTERVAL}"
done
