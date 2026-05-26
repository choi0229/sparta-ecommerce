#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

git config core.hooksPath .githooks

echo "[OK] Git hooks 경로가 .githooks 로 설정되었습니다."
echo "     이제 git commit 시 .githooks/pre-commit (Claude guardrails) 이 자동으로 실행됩니다."
echo ""
echo "     우회 방법 (긴급 상황만): git commit --no-verify"
echo "     설치 확인: git config core.hooksPath"
