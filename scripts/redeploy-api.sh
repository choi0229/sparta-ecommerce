#!/bin/bash

set -e

SERVICE=$1
REPLICAS=${2:-1}
NAMESPACE="ecommerce"
IMAGE_NAME="sparta-msa-final-project-${SERVICE}:latest"

if [ -z "$SERVICE" ]; then
  echo "사용법: ./scripts/redeploy-api.sh <service-name> [replicas]"
  echo "예시: ./scripts/redeploy-api.sh order-api"
  echo "지원 서비스: product-api, order-api, inventory-api, logistics-api"
  exit 1
fi

case "$SERVICE" in
  product-api|order-api|inventory-api|logistics-api)
    ;;
  *)
    echo "지원하지 않는 서비스입니다: $SERVICE"
    echo "지원 서비스: product-api, order-api, inventory-api, logistics-api"
    exit 1
    ;;
esac

echo "======================================"
echo "Service   : $SERVICE"
echo "Image     : $IMAGE_NAME"
echo "Namespace : $NAMESPACE"
echo "Replicas  : $REPLICAS"
echo "======================================"

echo "[1/8] 서비스 디렉터리로 이동"
cd "$SERVICE"

echo "[2/8] Kubernetes deployment scale down"
kubectl scale deployment "$SERVICE" --replicas=0 -n "$NAMESPACE"

echo "[3/8] 기존 Minikube image 제거"
minikube image rm "$IMAGE_NAME" || true

echo "[4/8] 현재 Minikube image 확인"
minikube image ls | grep "$SERVICE" || true

echo "[5/8] bootJar 생성"
./gradlew bootJar -x test

echo "[6/8] Docker image build"
docker build -t "$IMAGE_NAME" .

echo "[7/8] Minikube image load"
minikube image load "$IMAGE_NAME"

echo "[8/8] Kubernetes deployment scale up 및 rollout 확인"
kubectl scale deployment "$SERVICE" --replicas="$REPLICAS" -n "$NAMESPACE"
kubectl rollout status deployment "$SERVICE" -n "$NAMESPACE"

echo "======================================"
echo "배포 반영 완료: $SERVICE"
echo "======================================"