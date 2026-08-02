# Local LLM scripts

These scripts reproduce the Docker Model Runner integration and the initial
Qwen 2.5 3B evaluation.

## Initial setup

```bash
./scripts/local-llm/apply-repository-updates.sh

docker compose \
  -f compose.yaml \
  -f compose.local-llm.yaml \
  up -d --build --wait

./scripts/local-llm/verify-local-llm.sh
```

## Simple structured-output benchmark

```bash
./scripts/local-llm/run-simple-model-test.sh
./scripts/local-llm/profile-model.sh
```

The profiling script samples the `docker-model-runner` container and writes
untracked results under `build/local-llm-results/`.

## Full Agentic Sheets fixture

```bash
./scripts/local-llm/run-holdings-proposal.sh
```

A structurally rejected proposal returns curl status 22 while preserving and
printing the HTTP response body. This is expected for the recorded Qwen 2.5 3B
sum-type limitation.

## Hardware comparison

```bash
./scripts/local-llm/capture-environment.sh
./scripts/local-llm/profile-model.sh
./scripts/local-llm/run-holdings-proposal.sh || true
```

Keep the same Git commit, model tag and digest, context size, prompt, fixture,
and runtime flags for the first CPU-versus-GPU comparison.

## Before committing

```bash
./scripts/local-llm/check-checkin.sh
git diff
git status --short
```
