#!/bin/bash

set -euo pipefail

NAMESPACE="ecommerce"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ZOOKEEPER_MANIFEST="${REPO_ROOT}/deployment/infra/zookeeper.yaml"
KAFKA_MANIFEST="${REPO_ROOT}/deployment/infra/kafka.yaml"

# ── 환경 검증 ─────────────────────────────────────────────
echo "======================================"
echo "Kafka/Zookeeper 개발환경 초기화 스크립트"
echo "Namespace : ${NAMESPACE}"
echo "======================================"

if ! command -v minikube &>/dev/null; then
  echo "[ERROR] minikube 명령어를 찾을 수 없습니다. Minikube 환경에서만 실행하세요."
  exit 1
fi

if ! minikube status | grep -q "Running"; then
  echo "[ERROR] Minikube가 실행 중이지 않습니다. 'minikube start' 후 다시 시도하세요."
  exit 1
fi

if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
  echo "[ERROR] namespace '${NAMESPACE}'가 존재하지 않습니다."
  exit 1
fi

for f in "$ZOOKEEPER_MANIFEST" "$KAFKA_MANIFEST"; do
  if [[ ! -f "$f" ]]; then
    echo "[ERROR] manifest 파일을 찾을 수 없습니다: ${f}"
    exit 1
  fi
done

# ── 삭제 대상 안내 ────────────────────────────────────────
echo
echo "다음 리소스가 삭제됩니다:"
echo "  StatefulSet : kafka, zookeeper  (namespace: ${NAMESPACE})"
echo "  PVC         : data-kafka-0, data-zookeeper-0  (namespace: ${NAMESPACE})"
echo "  Minikube hostPath 볼륨 데이터 (kafka, zookeeper)"
echo
echo "주의: Kafka 토픽과 메시지, Zookeeper 메타데이터가 모두 초기화됩니다."
echo

# ── 사용자 확인 ───────────────────────────────────────────
read -r -p "정말 초기화할까요? [y/N] " confirm
case "$confirm" in
  [yY]) echo ;;
  *) echo "취소되었습니다."; exit 0 ;;
esac

# ── StatefulSet 삭제 ──────────────────────────────────────
echo "[1/6] StatefulSet 삭제"
kubectl delete statefulset kafka zookeeper -n "$NAMESPACE" --ignore-not-found=true
echo "  StatefulSet 삭제 완료. pod 종료 대기 중..."
kubectl wait --for=delete pod/kafka-0 -n "$NAMESPACE" --timeout=60s 2>/dev/null || true
kubectl wait --for=delete pod/zookeeper-0 -n "$NAMESPACE" --timeout=60s 2>/dev/null || true

# ── PVC 삭제 ──────────────────────────────────────────────
echo "[2/6] PVC 삭제"
kubectl delete pvc data-kafka-0 data-zookeeper-0 -n "$NAMESPACE" --ignore-not-found=true

# ── Minikube hostPath 정리 ────────────────────────────────
echo "[3/6] Minikube hostPath 볼륨 데이터 정리"
minikube ssh "sudo rm -rf /tmp/hostpath-provisioner/${NAMESPACE}/data-kafka-0 \
                            /tmp/hostpath-provisioner/${NAMESPACE}/data-zookeeper-0" 2>/dev/null || \
  echo "  [WARN] hostPath 정리 중 일부 오류가 발생했습니다. 계속 진행합니다."

# ── manifest 재적용 ───────────────────────────────────────
echo "[4/6] Zookeeper 재적용"
kubectl apply -f "$ZOOKEEPER_MANIFEST"

echo "[5/6] Kafka 재적용"
kubectl apply -f "$KAFKA_MANIFEST"

# ── 기동 확인 ─────────────────────────────────────────────
echo "[6/6] rollout 상태 및 endpoints 확인"

echo "  Zookeeper rollout 대기..."
kubectl rollout status statefulset/zookeeper -n "$NAMESPACE" --timeout=120s

echo "  Kafka rollout 대기..."
kubectl rollout status statefulset/kafka -n "$NAMESPACE" --timeout=120s

echo
echo "  Endpoints:"
kubectl get endpoints kafka zookeeper -n "$NAMESPACE"

echo
echo "======================================"
echo "Kafka/Zookeeper 초기화 완료"
echo "logistics-api, order-api를 재배포하려면:"
echo "  ./scripts/redeploy-api.sh logistics-api"
echo "  ./scripts/redeploy-api.sh order-api"
echo "======================================"
