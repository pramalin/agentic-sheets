#!/usr/bin/env bash
# Optional convenience environment for local-LLM scripts.
#
# Usage:
#   cp scripts/local-llm/environment.example.sh \
#      scripts/local-llm/environment.local.sh
#   source scripts/local-llm/environment.local.sh
#
# environment.local.sh is ignored after running apply-repository-updates.sh.

export BASE_URL="${BASE_URL:-http://localhost:8081}"
export AUTH="${AUTH:-Authorization: Bearer dev-local-secret}"

export FILE="${FILE:-holdings_jpmc_20260115.xlsx}"
export WORKSHEET="${WORKSHEET:-Holdings}"
export MODEL_ID="${MODEL_ID:-Holdings}"
export CLIENT_ID="${CLIENT_ID:-jpmc}"

export AGENTIC_SHEETS_LOCAL_MODEL="${AGENTIC_SHEETS_LOCAL_MODEL:-qwen2.5:3B-Q4_K_M}"
export AGENTIC_SHEETS_LOCAL_MODEL_THREADS="${AGENTIC_SHEETS_LOCAL_MODEL_THREADS:-4}"
export AGENTIC_SHEETS_LOCAL_MODEL_THREADS_BATCH="${AGENTIC_SHEETS_LOCAL_MODEL_THREADS_BATCH:-4}"

dc() {
  docker compose \
    -f compose.yaml \
    -f compose.local-llm.yaml \
    "$@"
}
