#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

failed=0

obsolete="backend/src/main/java/com/alai/agenticsheets/config/OpenAiHttpClientConfiguration.java"
if [[ -e "$obsolete" ]]; then
  echo "REVIEW: obsolete experimental timeout class still exists: $obsolete" >&2
  failed=1
fi

if git grep -nF 'Rejected model proposal:' -- ':!scripts/local-llm/check-checkin.sh' >/tmp/agentic-sheets-debug-log-matches 2>/dev/null; then
  echo "REVIEW: full rejected-proposal debug logging is still present:" >&2
  cat /tmp/agentic-sheets-debug-log-matches >&2
  failed=1
fi
rm -f /tmp/agentic-sheets-debug-log-matches

if git grep -nE 'sk-[A-Za-z0-9_-]{12,}' -- . >/tmp/agentic-sheets-secret-matches 2>/dev/null; then
  echo "REVIEW: possible OpenAI secret found:" >&2
  cat /tmp/agentic-sheets-secret-matches >&2
  failed=1
fi
rm -f /tmp/agentic-sheets-secret-matches

echo
echo "Git status"
git status --short

if (( failed != 0 )); then
  echo
echo "Check-in review found items requiring attention." >&2
  exit 1
fi

echo
echo "Check-in review passed. Inspect git diff before committing."
