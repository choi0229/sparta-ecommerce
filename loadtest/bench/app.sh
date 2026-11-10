#!/usr/bin/env bash
# 서비스 jar를 loadtest 프로필로 격리 환경(loadtest/env/compose.yml)에 붙여 실행하고 멈춘다.
# 사용법: app.sh start <product|inventory|order> [NAME=VALUE ...]
#         app.sh stop  <product|inventory|order>
# 예: app.sh start order PAYMENT_MOCK_SUCCESS_RATE=100
# jar는 미리 빌드한다: ./gradlew :order-api:bootJar (서비스별)
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

cmd="${1:?usage: app.sh start|stop <product|inventory|order> [NAME=VALUE ...]}"
name="${2:?service name required}"
shift 2
case "${name}" in
  product)   port=${PRODUCT_API_PORT};   db_port=15433; db=productdb ;;
  inventory) port=${INVENTORY_API_PORT}; db_port=15434; db=inventorydb ;;
  order)     port=${ORDER_API_PORT};     db_port=15435; db=orderdb ;;
  *) echo "unknown service: ${name}" >&2; exit 2 ;;
esac

mkdir -p "${RUNTIME_DIR}"
pid_file="${RUNTIME_DIR}/${name}.pid"
log_file="${RUNTIME_DIR}/${name}-api.log"

if [[ "${cmd}" == "stop" ]]; then
  if [[ -f "${pid_file}" ]]; then
    pid=$(cat "${pid_file}")
    kill "${pid}" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "${pid}" 2>/dev/null || break; sleep 2; done
    kill -0 "${pid}" 2>/dev/null && { echo "${name} still running (pid ${pid})"; exit 1; }
    rm -f "${pid_file}"
  fi
  echo "${name} stopped"
  exit 0
fi

[[ "${cmd}" == "start" ]] || { echo "unknown command: ${cmd}" >&2; exit 2; }
jar=$(ls "${REPO_DIR}/${name}-api/build/libs/"*.jar 2>/dev/null | grep -v plain | head -1)
[[ -n "${jar}" ]] || { echo "jar not found: ${name}-api/build/libs (./gradlew :${name}-api:bootJar)" >&2; exit 1; }

env SPRING_PROFILES_ACTIVE=loadtest SERVER_PORT="${port}" \
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${db_port}/${db}" \
  SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 "$@" \
  nohup java -jar "${jar}" > "${log_file}" 2>&1 &
echo $! > "${pid_file}"
# 측정 결과에 함께 남기도록 추가 설정을 기록한다
echo "${name}-api jar=$(basename "${jar}") $*" > "${RUNTIME_DIR}/${name}.args"
for i in $(seq 1 60); do
  curl -sf "localhost:${port}/actuator/health" > /dev/null && { echo "${name} UP after $((i * 2))s (pid $(cat "${pid_file}"), jar $(basename "${jar}"))"; exit 0; }
  sleep 2
done
echo "${name} NOT UP"; tail -30 "${log_file}"; exit 1
