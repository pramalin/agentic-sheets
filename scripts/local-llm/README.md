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

Historically (before the Local LLM phase's Steps LLM-1 through LLM-4), a
structurally rejected proposal returned curl status 22 while preserving and
printing the HTTP response body -- Qwen 2.5 3B reliably left currency and
asset_class variant resolution unresolved. That's the exact limitation those
steps moved into deterministic code; the current expected result against
today's code is very likely different (a clean 200, since the resolver now
fills in what the model leaves unresolved) but hasn't been re-confirmed yet
-- see the Step LLM-6 section below.

## Step LLM-6: re-scoped benchmark

```bash
./scripts/local-llm/run-llm6-benchmark.sh
```

See `docs/local-llm-enhancements.md`'s Step LLM-6 section for the full
reasoning. Runs the same model against two fixtures back to back: the
original `holdings_jpmc_20260115.xlsx` (re-establishing today's baseline,
per the note above) and `holdings_jpmc_llm6_unfamiliar_column.xlsx`
(byte-identical except one header, `Price` renamed to `Valuation Px` --
a column name nothing in this codebase has ever seen). The question this
answers: with currency/asset_class handled deterministically, can a 3B
model correctly resolve one genuinely unfamiliar column on its own.

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
