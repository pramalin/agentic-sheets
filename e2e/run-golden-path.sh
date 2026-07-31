#!/usr/bin/env bash
set -euo pipefail

# Runs the golden-path E2E test against a fully isolated Compose
# environment. Directory-independent -- resolves paths relative to this
# script's own location, not wherever it happened to be invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Unique per invocation, not a fixed name -- an external review
# correctly noted that a fixed project name means two worktrees, or two
# simultaneous local runs, would collide, and one run's cleanup could
# tear down the other's containers out from under it. GITHUB_RUN_ID is
# genuinely unique per CI workflow run; $$ (this shell's own PID) is a
# reasonable low-collision stand-in for local runs, where there's no
# equivalent built-in identifier.
RUN_ID="${GITHUB_RUN_ID:-$$}"
PROJECT_NAME="agentic-sheets-e2e-${RUN_ID}"
COMPOSE_ARGS=(-p "$PROJECT_NAME" -f compose.yaml -f compose.e2e.yaml)

# Distinct host ports, not the defaults -- a real collision this
# project actually hit: -p gives separate containers/networks/volumes,
# but `ports: "5432:5432"` still tries to bind the literal host port
# regardless of project name, so this collided with an ordinary dev
# stack that happened to be running at the same time. Overridable
# (`${VAR:-default}`), not unconditionally assigned. Worth being
# precise about what this does and doesn't guarantee, per a follow-up
# external review: the *project name* is unique per invocation (line
# 18 above), but these port defaults are still fixed -- two
# unconfigured concurrent local runs will still collide on ports even
# though their containers/networks/volumes no longer would. Parallel
# runs are supported *with* distinct port overrides, not automatically
# safe without them; a caller running two of these at once needs to
# actually set different values for each. Exported here so both the
# `docker compose` invocation below (via compose.yaml's own
# ${POSTGRES_PORT:-5432} / ${AGENTIC_SHEETS_BACKEND_PORT:-8081}
# interpolation) and Playwright's actual request target agree on the
# same values -- not hardcoded twice in two places that could drift
# apart.
export POSTGRES_PORT="${POSTGRES_PORT:-15432}"
export AGENTIC_SHEETS_BACKEND_PORT="${AGENTIC_SHEETS_BACKEND_PORT:-18081}"
export LLMSIM_HOST_PORT="${LLMSIM_HOST_PORT:-18089}"

# -p is load-bearing, not cosmetic -- see compose.e2e.yaml's own
# header comment for why reusing the ordinary dev Compose project would
# make this suite nondeterministic (a pre-existing postgres-data volume
# skips schema initialization entirely, since Postgres only runs
# docker-entrypoint-initdb.d against a brand-new volume).
cleanup() {
  # A real bug this project actually hit: the script cd's into e2e/
  # later on to run npm/playwright, and never cd's back before that --
  # when the failure trap fires from there (e.g. a failing test causes
  # `set -e` to exit while still inside e2e/), `docker compose` would
  # look for compose.yaml relative to the *current* directory (e2e/),
  # not the repo root, and fail with a confusing "no such file"
  # instead of actually tearing anything down. A trap can fire from
  # anywhere in the script, so cleanup() needs to be independent of
  # wherever that happened to be, not assume it's already at the repo
  # root.
  cd "$REPO_ROOT"
  echo "--- Capturing Compose logs before teardown ---"
  # Captured *before* teardown, not queried afterward -- the containers
  # are gone by the time this function returns, so a separate "show
  # logs on failure" step later in a CI job would find nothing to query.
  docker compose "${COMPOSE_ARGS[@]}" logs > "$REPO_ROOT/e2e/compose-logs.txt" 2>&1 || true
  echo "--- Tearing down E2E environment ($PROJECT_NAME) ---"
  docker compose "${COMPOSE_ARGS[@]}" down --volumes --remove-orphans || true
}
# Runs on normal exit *and* on failure -- a failed test run must not
# leave a stray isolated environment behind for the next run to
# collide with.
trap cleanup EXIT

echo "--- Starting E2E environment ($PROJECT_NAME) ---"
docker compose "${COMPOSE_ARGS[@]}" up --build --wait

# llmsim has no Compose-level healthcheck (uncertain whether curl/wget
# exists inside eclipse-temurin:21-jre-jammy to run one reliably) --
# `--wait` above only confirms the *other* services' existing
# healthchecks passed. Poll from here instead, where curl is known to
# exist.
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

echo "--- Running golden-path E2E test ---"
cd "$REPO_ROOT/e2e"
npm ci
# e2e-test-key must match compose.e2e.yaml's AGENTIC_SHEETS_API_KEY
# exactly -- a real first run of this suite failed with a 401 because
# these weren't wired together at all (the test fell back to this same
# default value, but the backend was still reading whatever a
# developer's local .env happened to have).
E2E_BACKEND_URL="http://localhost:${AGENTIC_SHEETS_BACKEND_PORT}" \
E2E_LLMSIM_URL="http://localhost:${LLMSIM_HOST_PORT}" \
E2E_API_KEY="e2e-test-key" \
  npx playwright test tests/pipeline-api.spec.ts
