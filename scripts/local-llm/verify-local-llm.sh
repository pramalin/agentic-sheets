#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BASE_URL="${BASE_URL:-http://localhost:8081}"

dc() {
  docker compose \
    -f compose.yaml \
    -f compose.local-llm.yaml \
    "$@"
}

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: required command not found: $1" >&2
    exit 1
  }
}

require docker
require curl

echo "Docker versions"
docker version --format 'Docker Engine: {{.Server.Version}}'
docker compose version
docker model version 2>/dev/null || true

echo
echo "Compose services"
dc ps

echo
echo "Backend health"
curl --fail --silent --show-error "$BASE_URL/actuator/health"
echo

echo
echo "Local-model configuration"
dc exec backend sh -eu -c '
  endpoint="${SPRING_AI_OPENAI_BASE_URL:-}"
  model="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-}"
  timeout="${SPRING_AI_OPENAI_TIMEOUT:-}"
  sdk_retries="${SPRING_AI_OPENAI_MAX_RETRIES:-}"
  spring_attempts="${SPRING_AI_RETRY_MAX_ATTEMPTS:-}"

  echo "Endpoint: ${endpoint:-missing}"
  echo "Model: ${model:-missing}"
  echo "Timeout: ${timeout:-missing}"
  echo "OpenAI SDK retries: ${sdk_retries:-missing}"
  echo "Spring AI attempts: ${spring_attempts:-missing}"

  test "$endpoint" = "http://model-runner.docker.internal:12434/engines/v1"
  test -n "$model"
  test "$timeout" = "PT5M"
  test "$sdk_retries" = "0"
  test "$spring_attempts" = "1"
  test "${OPENAI_API_KEY:-}" = "local-model-runner"

  echo "API-key mode: safe local placeholder"
'

echo
echo "Docker Model Runner"
docker model status 2>/dev/null || true
docker model list

echo
echo "Verification passed"
