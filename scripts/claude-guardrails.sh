#!/usr/bin/env bash
set -euo pipefail

FAIL=0

# Determine diff target: CI uses HEAD~1..HEAD, local uses --cached
if [[ "${CI:-}" == "true" ]]; then
  DIFF_CMD="git diff --name-only HEAD~1 HEAD"
else
  DIFF_CMD="git diff --name-only --cached"
fi

CHANGED_FILES=$($DIFF_CMD)

# 1. .DS_Store 파일 커밋 방지
if echo "$CHANGED_FILES" | grep -q '\.DS_Store'; then
  echo "[FAIL] .DS_Store 파일이 커밋 대상에 포함되어 있습니다."
  echo "       git rm --cached <파일경로> 로 제거하세요."
  FAIL=1
fi

# 2. .env / secret 파일 커밋 방지
if echo "$CHANGED_FILES" | grep -qE '(^|/)\.env($|\.|/)|(secret|credential|private_key|id_rsa)'; then
  echo "[FAIL] .env 또는 secret 파일이 커밋 대상에 포함되어 있습니다."
  echo "       민감 정보가 포함된 파일을 커밋하지 마세요."
  FAIL=1
fi

# 3. payment-api 디렉터리 생성 방지
if echo "$CHANGED_FILES" | grep -q '^payment-api/'; then
  echo "[FAIL] payment-api/ 디렉터리가 커밋 대상에 포함되어 있습니다."
  echo "       결제 흐름은 product-api 내 약식 구현으로 유지합니다. CLAUDE.md를 확인하세요."
  FAIL=1
fi

# 4. docs/claude-sessions 커밋 방지
if echo "$CHANGED_FILES" | grep -q '^docs/claude-sessions/'; then
  echo "[FAIL] docs/claude-sessions/ 파일이 커밋 대상에 포함되어 있습니다."
  echo "       세션 로그는 .gitignore에 추가하거나 git rm --cached 로 제거하세요."
  FAIL=1
fi

# 5. 위험한 명령어 패턴 감지 (문서/설정/스크립트 자체 제외)
DANGEROUS_PATTERN='(rm\s+-rf|DROP\s+TABLE|TRUNCATE\s+TABLE|force\s*push|git\s+push\s+.*--force)'
SCAN_FILES=$(echo "$CHANGED_FILES" | grep -vE '^(README\.md|scripts/claude-guardrails\.sh|\.claude/settings\.json|\.github/)' || true)

if [[ -n "$SCAN_FILES" ]]; then
  MATCHED=$(echo "$SCAN_FILES" | while read -r f; do
    [[ -f "$f" ]] && grep -lP "$DANGEROUS_PATTERN" "$f" 2>/dev/null || true
  done)
  if [[ -n "$MATCHED" ]]; then
    echo "[FAIL] 위험한 명령어 패턴이 감지되었습니다:"
    echo "$MATCHED" | sed 's/^/       /'
    echo "       rm -rf / DROP TABLE / TRUNCATE / force push 등이 포함된 파일을 확인하세요."
    FAIL=1
  fi
fi

if [[ "$FAIL" -eq 0 ]]; then
  echo "Claude guardrails passed."
fi

exit "$FAIL"
