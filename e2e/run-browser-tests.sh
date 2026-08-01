#!/usr/bin/env bash
set -euo pipefail

# Runs Checkpoint B's browser journeys (review-approval,
# api-key-recovery) against a fully isolated Compose environment plus a
# real Vite dev server and real Chromium. Directory-independent --
# resolves paths relative to this script's own location, not wherever
# it happened to be invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Unique per invocation, not a fixed name -- same reasoning as
# run-golden-path.sh: a fixed project name means two worktrees, or two
# simultaneous local runs, would collide.
RUN_ID="${GITHUB_RUN_ID:-$$}"
PROJECT_NAME="agentic-sheets-e2e-browser-${RUN_ID}"
COMPOSE_ARGS=(-p "$PROJECT_NAME" -f compose.yaml -f compose.e2e.yaml)

# Distinct from run-golden-path.sh's own defaults (15432/18081/18089),
# not just from the ordinary dev stack's -- so the two E2E scripts
# could in principle run at the same time locally without colliding
# with each other either, even though neither is currently designed to
# be invoked that way on purpose.
export POSTGRES_PORT="${POSTGRES_PORT:-35432}"
export AGENTIC_SHEETS_BACKEND_PORT="${AGENTIC_SHEETS_BACKEND_PORT:-38081}"
export LLMSIM_HOST_PORT="${LLMSIM_HOST_PORT:-38089}"
export E2E_FRONTEND_PORT="${E2E_FRONTEND_PORT:-38173}"

cleanup() {
  # Same real bug run-golden-path.sh already found and fixed: a trap
  # can fire from anywhere in the script, so this needs to be
  # independent of wherever that happened to be, not assume it's
  # already at the repo root.
  cd "$REPO_ROOT"
  echo "--- Capturing Compose logs before teardown ---"
  timeout 60 docker compose "${COMPOSE_ARGS[@]}" logs > "$REPO_ROOT/e2e/compose-logs-browser.txt" 2>&1 || true
  echo "--- Tearing down E2E environment ($PROJECT_NAME) ---"
  # timeout, not just || true -- a real run of run-golden-path.sh's own
  # equivalent call hung for 5+ minutes despite the CLI's own progress
  # output showing every resource already Removed, needing a manual
  # Ctrl-C. Same defensive bound applied here for consistency, even
  # though this specific script hasn't shown the issue itself yet.
  timeout 90 docker compose "${COMPOSE_ARGS[@]}" down --volumes --remove-orphans || true
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

echo "--- Installing Chromium (skips cleanly if already cached) ---"
cd "$REPO_ROOT/e2e"
npm ci
# --with-deps only in CI -- installing system packages on a local dev
# machine without asking first isn't this script's call to make;
# CI runners are throwaway and expect exactly this.
if [ -n "${CI:-}" ]; then
  npx playwright install --with-deps chromium
else
  npx playwright install chromium
fi

echo "--- Running Checkpoint B browser journeys ---"
E2E_BACKEND_URL="http://localhost:${AGENTIC_SHEETS_BACKEND_PORT}" \
E2E_LLMSIM_URL="http://localhost:${LLMSIM_HOST_PORT}" \
E2E_FRONTEND_URL="http://localhost:${E2E_FRONTEND_PORT}" \
E2E_API_KEY="e2e-test-key" \
E2E_START_FRONTEND="true" \
  npx playwright test --project=browser
