#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODEL="${AGENTIC_SHEETS_LOCAL_MODEL:-qwen2.5:3B-Q4_K_M}"
PROMPT_FILE="${PROMPT_FILE:-$SCRIPT_DIR/simple-mapping-prompt.txt}"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is not installed or not on PATH" >&2
  exit 1
fi

if [[ ! -r "$PROMPT_FILE" ]]; then
  echo "ERROR: prompt file is not readable: $PROMPT_FILE" >&2
  exit 1
fi

PROMPT="$(cat "$PROMPT_FILE")"
docker model run "$MODEL" "$PROMPT"
