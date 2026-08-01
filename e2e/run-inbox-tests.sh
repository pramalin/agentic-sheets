#!/usr/bin/env bash
set -euo pipefail

# Runs the inbox-scanner E2E test against a fully isolated Compose
# environment. Directory-independent -- resolves paths relative to this
# script's own location, not wherever it happened to be invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Unique per invocation -- same reasoning as the other two E2E scripts.
RUN_ID="${GITHUB_RUN_ID:-$$}"
PROJECT_NAME="agentic-sheets-e2e-inbox-${RUN_ID}"
COMPOSE_ARGS=(-p "$PROJECT_NAME" -f compose.yaml -f compose.e2e.yaml -f compose.e2e-inbox.yaml)

# Distinct from both other E2E scripts' own defaults (15432/18081/18089
# and 35432/38081/38089/38173) -- so all three could in principle run
# at the same time locally without colliding with each other.
export POSTGRES_PORT="${POSTGRES_PORT:-45432}"
export AGENTIC_SHEETS_BACKEND_PORT="${AGENTIC_SHEETS_BACKEND_PORT:-48081}"
export LLMSIM_HOST_PORT="${LLMSIM_HOST_PORT:-48089}"

# A real mktemp -d, not anything git-tracked -- see compose.e2e-inbox.yaml's
# own header comment for why this needs to be a throwaway directory,
# never sample-input/ itself.
export E2E_INBOX_HOST_DIR="$(mktemp -d -t agentic-sheets-e2e-inbox-XXXXXX)"
mkdir -p "$E2E_INBOX_HOST_DIR/inbox" "$E2E_INBOX_HOST_DIR/archive"

cleanup() {
  cd "$REPO_ROOT"
  echo "--- Capturing Compose logs before teardown ---"
  docker compose "${COMPOSE_ARGS[@]}" logs > "$REPO_ROOT/e2e/compose-logs-inbox.txt" 2>&1 || true
  echo "--- Tearing down E2E environment ($PROJECT_NAME) ---"
  docker compose "${COMPOSE_ARGS[@]}" down --volumes --remove-orphans || true

  # The backend container runs as root (no USER directive in its own
  # Dockerfile) -- InboxArchiver's file moves happen as root, so a
  # plain host-side rm -rf by this script's own (non-root) user can
  # genuinely fail with "Permission denied" on whatever subdirectories
  # the archiver created. Not fatal -- || true, matching this project's
  # own established pattern for best-effort cleanup steps -- but named
  # explicitly rather than silently swallowed: a failure here just
  # means $E2E_INBOX_HOST_DIR needs an occasional manual `sudo rm -rf`
  # under /tmp, not that anything about the test run itself is wrong.
  echo "--- Removing temp workspace ($E2E_INBOX_HOST_DIR) ---"
  rm -rf "$E2E_INBOX_HOST_DIR" \
    || echo "  (couldn't fully remove it -- likely root-owned files from the container; safe to ignore or clean up manually later)"
}
trap cleanup EXIT

echo "--- Starting E2E environment ($PROJECT_NAME) ---"
docker compose "${COMPOSE_ARGS[@]}" up --build --wait

echo "--- Waiting for llmsim to actually be ready ---"
READY=false
for i in $(seq 1 30); do
  if curl -sf "http://localhost:${LLMSIM_HOST_PORT}/_llmsim/status" > /dev/null 2>&1; then
    echo "llmsim is ready."
    READY=true
    break
  fi
  sleep 1
done
if [ "$READY" != "true" ]; then
  echo "llmsim did not become ready within 30s." >&2
  docker compose "${COMPOSE_ARGS[@]}" logs llmsim
  exit 1
fi

echo "--- Running inbox-scanner E2E test ---"
cd "$REPO_ROOT/e2e"
npm ci
E2E_BACKEND_URL="http://localhost:${AGENTIC_SHEETS_BACKEND_PORT}" \
E2E_LLMSIM_URL="http://localhost:${LLMSIM_HOST_PORT}" \
E2E_API_KEY="e2e-test-key" \
E2E_INBOX_HOST_DIR="$E2E_INBOX_HOST_DIR" \
  npx playwright test --project=inbox tests/inbox-scanner.spec.ts
