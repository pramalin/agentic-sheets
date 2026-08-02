# Local LLM Evaluation

Status: experimental  
Initial evaluation date: 2026-08-02

## Objective

Evaluate whether Agentic Sheets can use a locally hosted language model for
mapping proposals while preserving the existing deterministic validation and
human-approval workflow.

The local model replaces only the hosted inference endpoint. It does not
replace structural validation, canonical conversion, approval, or delivery.

## Architecture

```text
AgentMappingProposalService
    -> Spring AI OpenAI-compatible client
    -> Docker Model Runner
    -> Qwen 2.5 3B GGUF through llama.cpp
    -> MappingProposalStructuralValidator
```

## Local integration configuration

- Overlay: `compose.local-llm.yaml`
- OpenAI-compatible endpoint:
  `http://model-runner.docker.internal:12434/engines/v1`
- API key: non-secret local placeholder
- Client timeout: five minutes
- OpenAI SDK retries: zero
- Spring AI attempts: one

The timeout and retry settings matter on CPU-only hardware. Earlier runs were
cancelled at approximately 60 seconds and retried, producing a total duration
near 245 seconds without a completed response.

## Model configuration

- Model: `qwen2.5:3B-Q4_K_M`
- Model digest:
  `sha256:41045df49cc0d72a4f8c15eb6b21464d3e6f4dc2899fe8ccd9e5b72bdf4d0bf9`
- Inference engine: llama.cpp
- Context size: 4096
- Threads: 4
- Batch threads: 4
- Temperature: 0
- Top-k: 1
- Initial hardware: CPU-only Chromebook Linux environment
- Model Runner memory: approximately 3.31 GiB

## Simple structured-output test

Input columns:

- `Account Number`
- `Security Description`
- `Market Value`

Expected mappings:

- `Account Number -> account.id`
- `Security Description -> holding.securityName`
- `Market Value -> holding.marketValue.amount`

Observed results:

| Run | Configuration | Elapsed | CPU | Result |
|---|---|---:|---:|---|
| 1 | unrestricted warm | 8.69 s | about 700-800% | correct JSON |
| 2 | four threads after reload | 11.28 s | about 350-404% | correct JSON |
| 3 | four-thread warm | 8.39 s | about 350-403% | correct JSON |

Four threads reduced CPU consumption substantially without increasing the warm
latency in this small test.

## Agentic Sheets holdings fixture

Fixture:

- File: `holdings_jpmc_20260115.xlsx`
- Worksheet: `Holdings`
- Source columns: 11
- Data rows: 4

The full proposal decoded successfully into `MappingProposal`. Its source field
correspondence was strong:

- Source-column mappings: 11 of 11 correct
- Hallucinated source columns: none
- Unmapped source columns: none
- Sum-type completion: 0 of 2
- Structural validation: failed, as designed

Repeated validation problems:

- `asset_class` had neither `selectedVariant` nor `variantValueMap`
- `currency` had neither `selectedVariant` nor `variantValueMap`

Recorded completed inference times:

| Run | Elapsed | HTTP result |
|---|---:|---|
| 1 | 137.002 s | 422 structural-validation failure |
| 2 | 197.157 s | 422 structural-validation failure |
| 3 | 198.675 s | 422 structural-validation failure |

The full rejected proposal showed that Qwen correctly mapped the semantic fields
but omitted the conditional sum-type metadata. The deterministic validator
prevented persistence of an incomplete proposal.

## Interpretation

Qwen 2.5 3B performed the source-to-canonical field correspondence correctly.
Its observed weakness was narrow but important: it did not reliably satisfy the
conditional sum-type contract inside the larger structured-output task.

The validator must not be weakened. Candidate follow-up work includes:

1. Prompt and output-schema hardening.
2. A deterministic `SumTypeMappingResolver` between model output and structural
   validation, filling only unambiguous missing metadata.
3. Comparison with stronger local models.

A deterministic resolver must inspect all relevant distinct source values, not
only display samples. A fixed `selectedVariant` is data-dependent and must not
be inferred from a partial sample when a file can contain several variants.

## Reproduction

Start the stack:

```bash
docker compose \
  -f compose.yaml \
  -f compose.local-llm.yaml \
  up -d --build --wait
```

Verify configuration:

```bash
./scripts/local-llm/verify-local-llm.sh
```

Run the small model test:

```bash
./scripts/local-llm/profile-model.sh
```

Run the holdings evaluation:

```bash
./scripts/local-llm/run-holdings-proposal.sh
```

Results are written under `build/local-llm-results/` and are intentionally not
tracked by Git.

### Bundled reproduction run

Date: 2026-08-02

Environment verification:

- Docker Engine: 29.2.1
- Docker Compose: 5.0.2
- Docker Model Runner: 1.2.6
- Backend: healthy
- Sheets MCP: healthy
- PostgreSQL: healthy
- Model: `qwen2.5:3B-Q4_K_M`
- Backend: llama.cpp
- Context size: 4096
- Threads: 4
- OpenAI-compatible timeout: 5 minutes
- OpenAI SDK retries: 0
- Spring AI attempts: 1

Results:

| Test | Elapsed | Result |
|---|---:|---|
| Simple mapping, model load included | 20.20 s | Correct |
| Full holdings proposal | 190.84 s | HTTP 422 |

The full proposal again mapped all source columns correctly but omitted
sum-type resolution for `asset_class` and `currency`.

## Current conclusion

The local-model integration is operational. Qwen 2.5 3B is useful for continued
experimentation and mapped all observed source columns correctly, but it does
not yet produce an accepted holdings proposal without sum-type enrichment or
stronger conditional instruction following.
