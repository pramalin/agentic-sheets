#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BASE_URL="${BASE_URL:-http://localhost:8081}"
AUTH="${AUTH:-Authorization: Bearer dev-local-secret}"
FILE="${FILE:-holdings_jpmc_20260115.xlsx}"
WORKSHEET="${WORKSHEET:-Holdings}"
MODEL_ID="${MODEL_ID:-Holdings}"
CLIENT_ID="${CLIENT_ID:-jpmc}"
MAX_TIME_SECONDS="${MAX_TIME_SECONDS:-360}"
RESULTS_DIR="${RESULTS_DIR:-build/local-llm-results}"

if [[ ! -x /usr/bin/time ]]; then
  echo "ERROR: /usr/bin/time is required (install the 'time' package)" >&2
  exit 1
fi

for command in curl jq; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "ERROR: required command not found: $command" >&2
    exit 1
  }
done

mkdir -p "$RESULTS_DIR"
TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
HOST_NAME="$(hostname | tr -cs '[:alnum:]_.-' '-')"
OUTPUT_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-holdings-response.json"
TIMING_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-holdings-timing.txt"
HTTP_FILE="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-holdings-http.txt"

URL="${BASE_URL}/internal/mapping/propose?modelId=${MODEL_ID}&clientId=${CLIENT_ID}&path=${FILE}&worksheet=${WORKSHEET}"

echo "Model ID:   $MODEL_ID"
echo "Client ID:  $CLIENT_ID"
echo "File:       $FILE"
echo "Worksheet:  $WORKSHEET"
echo "Max time:   ${MAX_TIME_SECONDS}s"
echo "Response:   $OUTPUT_FILE"
echo

set +e
/usr/bin/time \
  -f 'Elapsed: %E\nClient peak memory: %M KB' \
  -o "$TIMING_FILE" \
  curl \
    --max-time "$MAX_TIME_SECONDS" \
    --fail-with-body \
    --silent \
    --show-error \
    --request POST \
    --header "$AUTH" \
    --write-out '%{http_code}\n' \
    --output "$OUTPUT_FILE" \
    "$URL" >"$HTTP_FILE"
status=$?
set -e

http_status="$(tail -n 1 "$HTTP_FILE" 2>/dev/null || true)"

echo "HTTP status: ${http_status:-unknown}"
cat "$TIMING_FILE"

echo
echo "Response:"
if [[ -s "$OUTPUT_FILE" ]]; then
  jq . "$OUTPUT_FILE" 2>/dev/null || cat "$OUTPUT_FILE"
else
  echo "<empty response body>"
fi

echo
echo "curl exit status: $status"
echo "Artifacts:"
echo "  Response: $OUTPUT_FILE"
echo "  Timing:   $TIMING_FILE"
echo "  HTTP:     $HTTP_FILE"

exit "$status"
