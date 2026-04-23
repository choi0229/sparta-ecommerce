#!/bin/bash

NAMESPACE="ecommerce"
PASS=0
FAIL=0

pass() { echo "  [PASS] $1"; ((PASS++)); }
fail() { echo "  [FAIL] $1"; ((FAIL++)); }
section() { echo; echo "=== $1 ==="; }

# ── Pod 상태 ──────────────────────────────────────────────
section "Pod 상태"

for app in zookeeper kafka; do
  phase=$(kubectl get pod "${app}-0" -n "$NAMESPACE" \
    --no-headers -o custom-columns="PHASE:.status.phase" 2>/dev/null)
  ready=$(kubectl get pod "${app}-0" -n "$NAMESPACE" \
    --no-headers -o custom-columns="READY:.status.containerStatuses[0].ready" 2>/dev/null)

  if [[ "$phase" == "Running" && "$ready" == "true" ]]; then
    pass "${app}-0  phase=$phase  ready=$ready"
  else
    fail "${app}-0  phase=${phase:-NOT_FOUND}  ready=${ready:-NOT_FOUND}"
  fi
done

# ── Service / Endpoints ───────────────────────────────────
section "Service / Endpoints"

for svc in zookeeper kafka; do
  svc_exists=$(kubectl get svc "$svc" -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l | tr -d ' ')
  if [[ "$svc_exists" -ge 1 ]]; then
    pass "svc/${svc} 존재"
  else
    fail "svc/${svc} 없음"
  fi

  ep_ready=$(kubectl get endpoints "$svc" -n "$NAMESPACE" \
    -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)
  if [[ -n "$ep_ready" ]]; then
    pass "endpoints/${svc} ready  ip=${ep_ready}"
  else
    fail "endpoints/${svc} ready 주소 없음"
  fi
done

# ── kafka-0 로그 (최근 20줄) ──────────────────────────────
section "kafka-0 최근 로그 (20줄)"
kubectl logs kafka-0 -n "$NAMESPACE" --tail=20 2>/dev/null || echo "  [WARN] 로그를 가져올 수 없습니다."

# ── 결과 요약 ─────────────────────────────────────────────
section "결과 요약"
TOTAL=$((PASS + FAIL))
echo "  PASS: ${PASS} / ${TOTAL}"
echo "  FAIL: ${FAIL} / ${TOTAL}"
echo

if [[ "$FAIL" -eq 0 ]]; then
  echo "  상태: PASS — Kafka/Zookeeper 정상"
  exit 0
else
  echo "  상태: FAIL — 문제가 감지되었습니다. reset-kafka-dev.sh 실행을 고려하세요."
  exit 1
fi
