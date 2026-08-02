# Local LLM GPU Comparison Plan

Use this plan to compare the CPU-only Chromebook result with the gaming laptop.

## Fair-comparison controls

Keep these identical for the first comparison:

- Agentic Sheets Git commit
- Docker Model Runner version and inference engine
- Model tag and digest
- `compose.local-llm.yaml`
- Context size and runtime flags
- Prompt and structured-output schema
- Spreadsheet fixture and worksheet
- Temperature and top-k

Changing the model, engine, prompt, or thread configuration creates a separate
optimization experiment rather than a hardware-only comparison.

## Gaming-laptop preparation

1. Install and verify the vendor GPU driver.
2. Configure Docker Model Runner for the supported GPU backend.
3. Pull `qwen2.5:3B-Q4_K_M` and confirm its digest matches the CPU test.
4. Check Model Runner logs during inference. The CPU environment reported that
   no usable GPU was found; the gaming laptop should instead report GPU use or
   layer offloading.

Useful host checks:

```bash
nvidia-smi || true
rocm-smi || true
docker model status
docker model list
docker model inspect qwen2.5:3B-Q4_K_M
```

Capture the complete environment:

```bash
./scripts/local-llm/capture-environment.sh
```

## Benchmark sequence

Cold simple run:

```bash
docker model unload qwen2.5:3B-Q4_K_M || true
./scripts/local-llm/profile-model.sh
```

Three warm simple runs:

```bash
for run in 1 2 3; do
  echo "Warm simple run $run"
  ./scripts/local-llm/profile-model.sh
done
```

Three full workflow runs:

```bash
for run in 1 2 3; do
  echo "Holdings workflow run $run"
  ./scripts/local-llm/run-holdings-proposal.sh || true
done
```

The workflow command may return nonzero when the model response is structurally
rejected. Preserve the JSON response and timing files; quality and latency are
both comparison dimensions.

## Result table

| Metric | Chromebook CPU | Gaming laptop GPU |
|---|---:|---:|
| Git commit | | |
| Docker Model Runner version | | |
| Model digest | `41045df...` | |
| Context size | 4096 | 4096 |
| Runtime threads | 4 | 4 for initial comparison |
| Cold simple latency | about 11.28 s | |
| Warm simple median | about 8.39 s | |
| Full proposal median | about 197 s | |
| Prompt processing rate | about 26 tokens/s | |
| Generation rate | about 6-7 tokens/s | |
| Model RAM/VRAM | about 3.31 GiB RAM | |
| Correct source mappings | 11/11 | |
| Sum-type completion | 0/2 | |
| Structural validation | fail | |

## Separate GPU-optimized experiment

After the controlled comparison, tune the gaming laptop independently. Record
changes such as inference backend, GPU offload settings, context size, model,
quantization, or output-token limits as a separate experiment so the controlled
baseline remains interpretable.
