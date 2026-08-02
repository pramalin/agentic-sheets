# Local LLM bundle

This bundle contains additive files for the Docker Model Runner integration,
reproduction scripts, the recorded CPU-only Qwen evaluation, and a gaming-laptop
comparison plan.

It deliberately does not overwrite the repository's existing `README.md` or
`.gitignore`. After overlaying the bundle, run:

```bash
./scripts/local-llm/apply-repository-updates.sh
```

That script idempotently adds the README link and Git-ignore entries.

Then validate the files:

```bash
bash -n scripts/local-llm/*.sh

docker compose \
  -f compose.yaml \
  -f compose.local-llm.yaml \
  config >/tmp/agentic-sheets-local-llm-compose.yaml

./scripts/local-llm/verify-local-llm.sh
./scripts/local-llm/check-checkin.sh
```

Review before committing:

```bash
git status --short
git diff
```

Suggested commits:

```bash
git add compose.local-llm.yaml scripts/local-llm .gitignore README.md
git commit -m "feat: add Docker Model Runner local LLM setup"

git add docs/local-llm-evaluation.md docs/local-llm-gpu-comparison.md LOCAL_LLM_BUNDLE.md
git commit -m "docs: record local LLM evaluation and GPU comparison plan"
```

Known cleanup checks are included in `check-checkin.sh`. In particular, review
and remove the temporary full rejected-proposal debug log and any obsolete
experimental HTTP-client configuration before committing.
