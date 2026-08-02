#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODEL_RUNNER_CONTAINER="${MODEL_RUNNER_CONTAINER:-docker-model-runner}"
SAMPLE_SECONDS="${MODEL_STATS_SAMPLE_SECONDS:-0.5}"
RESULTS_DIR="${RESULTS_DIR:-build/local-llm-results}"

if [[ ! -x /usr/bin/time ]]; then
  echo "ERROR: /usr/bin/time is required (install the 'time' package)" >&2
  exit 1
fi

mkdir -p "$RESULTS_DIR"
TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
HOST_NAME="$(hostname | tr -cs '[:alnum:]_.-' '-')"
STATS_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-model-runner-stats.log"
TIMING_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-simple-timing.txt"

stats_pid=""
cleanup() {
  if [[ -n "$stats_pid" ]]; then
    kill "$stats_pid" 2>/dev/null || true
    wait "$stats_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if docker inspect "$MODEL_RUNNER_CONTAINER" >/dev/null 2>&1; then
  (
    while true; do
      printf '%s ' "$(date '+%H:%M:%S.%3N')"
      docker stats "$MODEL_RUNNER_CONTAINER" \
        --no-stream \
        --format 'CPU={{.CPUPerc}} MEM={{.MemUsage}}' || true
      sleep "$SAMPLE_SECONDS"
    done
  ) >"$STATS_FILE" &
  stats_pid=$!
else
  echo "WARNING: container '$MODEL_RUNNER_CONTAINER' was not found; stats will not be sampled" >&2
  : >"$STATS_FILE"
fi

set +e
/usr/bin/time \
  -f 'Elapsed: %E  Client peak memory: %M KB' \
  -o "$TIMING_FILE" \
  "$SCRIPT_DIR/run-simple-model-test.sh"
status=$?
set -e

cleanup
stats_pid=""
trap - EXIT INT TERM

echo
cat "$TIMING_FILE"

echo
echo "Model Runner samples:"
cat "$STATS_FILE"

echo
echo "Artifacts:"
echo "  Timing: $TIMING_FILE"
echo "  Stats:  $STATS_FILE"

exit "$status"
