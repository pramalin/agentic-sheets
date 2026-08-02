# Local LLM Evaluation

Status: experimental  
Initial evaluation date: 2026-08-02  
Latest update: 2026-08-02

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

## Simple structured-output test

Input columns:

- `Account Number`
- `Security Description`
- `Market Value`

Expected mappings:

- `Account Number -> account.id`
- `Security Description -> holding.securityName`
- `Market Value -> holding.marketValue.amount`

All recorded runs returned the expected JSON mapping.

### Initial Chromebook measurements

| Run | Configuration | Elapsed | CPU | Result |
|---|---|---:|---:|---|
| 1 | unrestricted warm | 8.69 s | about 700-800% | correct JSON |
| 2 | four threads after reload | 11.28 s | about 350-404% | correct JSON |
| 3 | four-thread warm | 8.39 s | about 350-403% | correct JSON |

Four threads reduced CPU consumption substantially without increasing the warm
latency in this small test.

### Bundled Chromebook reproduction

The checked-in scripts were rerun after a clean Compose restart:

- Docker Engine: 29.2.1
- Docker Compose: 5.0.2
- Docker Model Runner: 1.2.6
- Model Runner backend: llama.cpp
- Available container memory: 14.15 GiB

| Test | Elapsed | Peak observed Model Runner memory | Result |
|---|---:|---:|---|
| Simple mapping, model load included | 20.20 s | about 2.42 GiB | correct JSON |
| Full holdings proposal | 190.84 s | not captured by this script | HTTP 422 |

The simple-test CPU samples reached approximately 400%, consistent with the
configured four inference threads.

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

| Run | Environment | Elapsed | HTTP result |
|---|---|---:|---|
| 1 | Chromebook CPU, initial evaluation | 137.002 s | 422 structural-validation failure |
| 2 | Chromebook CPU, initial evaluation | 197.157 s | 422 structural-validation failure |
| 3 | Chromebook CPU, initial evaluation | 198.675 s | 422 structural-validation failure |
| 4 | Chromebook CPU, bundled reproduction | 190.84 s | 422 structural-validation failure |
| 5 | Gaming laptop, CPU fallback before driver repair | 300.98 s | 422 structural-validation failure |

Every completed full run reproduced the same two validation problems. The full
rejected proposal showed that Qwen correctly mapped the semantic fields but
omitted the conditional sum-type metadata. The deterministic validator
prevented persistence of an incomplete proposal.

## Gaming-laptop result

The gaming laptop has an NVIDIA GeForce GTX 1050 Ti with 4 GiB VRAM. After a
reboot, the NVIDIA 580.173.02 driver, loaded kernel module, and installed module
version matched. GPU access from an ordinary Docker container was also verified
with `nvidia-smi`.

Docker Model Runner was then reinstalled with:

```bash
docker model reinstall-runner \
  --backend llama.cpp \
  --gpu cuda
```

The runner pulled its CUDA image but reported:

```text
installed llama-server gpuSupport=false
```

Post-reboot simple-test measurements still showed CPU-dominant execution:

| Run | Elapsed | Model Runner CPU | Model Runner memory | Result |
|---|---:|---:|---:|---|
| Model load included | 28.62 s | up to about 299% | about 3.37 GiB | correct JSON |
| Warm | 15.44 s | about 270-298% | about 3.37 GiB | correct JSON |

This is not a successful GPU benchmark. It is a CPU-fallback result on the
gaming laptop.

The compatibility explanation is documented in
[`local-llm-gpu-comparison.md`](local-llm-gpu-comparison.md). In summary, the
GTX 10 series uses NVIDIA's Pascal architecture, while the current Docker Model
Runner CUDA variant is based on a CUDA 13 llama.cpp server. NVIDIA removed
Pascal offline-compilation and library support from CUDA Toolkit 13.0. The same
GPU remains usable with software built using a CUDA 12.x toolchain, but that
would be a separate experiment from the current Docker Model Runner setup.

## Interpretation

Qwen 2.5 3B performed the source-to-canonical field correspondence correctly.
Its observed weakness was narrow but important: it did not reliably satisfy the
conditional sum-type contract inside the larger structured-output task.

The validator must not be weakened. Candidate follow-up work includes:

1. Prompt and output-schema hardening.
2. A deterministic `SumTypeMappingResolver` between model output and structural
   validation, filling only unambiguous missing metadata.
3. Comparison with stronger local models on compatible hardware.

A deterministic resolver must inspect all relevant distinct source values, not
only display samples. A fixed `selectedVariant` is data-dependent and must not
be inferred from a partial sample when a file can contain several variants.

The gaming-laptop exercise also established an infrastructure result: host
NVIDIA support and Docker GPU passthrough can be healthy while a particular
inference image remains incompatible with an older GPU architecture.

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

## Current conclusion

The local-model integration is operational and reproducible. Qwen 2.5 3B mapped
all observed source columns correctly, but it does not yet produce an accepted
holdings proposal without sum-type enrichment or stronger conditional
instruction following.

The attempted Docker Model Runner GPU comparison on the GTX 1050 Ti did not
activate GPU inference because the current CUDA 13 runner path is incompatible
with Pascal. The recorded gaming-laptop numbers are CPU-fallback measurements,
not evidence of GPU performance.

## References

- [Docker Model Runner](https://docs.docker.com/ai/model-runner/)
- [Docker Model Runner inference engines](https://docs.docker.com/ai/model-runner/inference-engines/)
- [Docker Model Runner source: CUDA variant maps to llama.cpp `server-cuda13`](https://github.com/docker/model-runner)
- [NVIDIA CUDA Toolkit 13.0 release notes: Pascal support removed](https://docs.nvidia.com/cuda/archive/13.0.0/cuda-toolkit-release-notes/index.html)
- [NVIDIA GeForce architecture comparison: GTX 10 Series is Pascal](https://www.nvidia.com/en-us/geforce/graphics-cards/compare/)
- [llama.cpp Docker images: separate CUDA 12 and CUDA 13 variants](https://github.com/ggml-org/llama.cpp/blob/master/docs/docker.md)
