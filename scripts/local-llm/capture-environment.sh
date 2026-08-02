#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

MODEL="${AGENTIC_SHEETS_LOCAL_MODEL:-qwen2.5:3B-Q4_K_M}"
RESULTS_DIR="${RESULTS_DIR:-build/local-llm-results}"
mkdir -p "$RESULTS_DIR"

TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
HOST_NAME="$(hostname | tr -cs '[:alnum:]_.-' '-')"
OUTPUT_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-environment.txt"

{
  echo "Captured (UTC): $(date -u --iso-8601=seconds 2>/dev/null || date -u)"
  echo "Hostname: $(hostname)"
  echo

  echo "== Git =="
  git rev-parse HEAD 2>/dev/null || true
  git status --short 2>/dev/null || true
  echo

  echo "== Operating system =="
  uname -a
  if [[ -r /etc/os-release ]]; then
    cat /etc/os-release
  fi
  echo

  echo "== CPU and memory =="
  command -v lscpu >/dev/null 2>&1 && lscpu || true
  command -v free >/dev/null 2>&1 && free -h || true
  echo

  echo "== GPU =="
  command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi || echo "nvidia-smi not available"
  command -v rocm-smi >/dev/null 2>&1 && rocm-smi || true
  echo

  echo "== Docker =="
  docker version 2>&1 || true
  docker compose version 2>&1 || true
  docker model version 2>&1 || true
  docker model status 2>&1 || true
  echo

  echo "== Model =="
  echo "Requested model: $MODEL"
  docker model list 2>&1 || true
  docker model inspect "$MODEL" 2>&1 || true
  echo

  echo "== Model Runner container =="
  docker inspect docker-model-runner 2>&1 || true
} | tee "$OUTPUT_FILE"

echo
echo "Environment captured in $OUTPUT_FILE"
