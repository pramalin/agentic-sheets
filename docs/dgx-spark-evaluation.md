# DGX Spark Evaluation

Status: in progress  
Initial DGX Spark validation: 2026-08-07

## Objective

Evaluate Agentic Sheets on NVIDIA DGX Spark as part of a broader local-AI
portfolio, while keeping the application independent of any single inference
runtime.

The intended architecture is:

```text
Agentic Sheets
    -> Spring AI OpenAI-compatible client
    -> local inference endpoint
        -> llama.cpp on DGX Spark
        -> vLLM on DGX Spark
        -> other OpenAI-compatible runtimes as follow-up experiments
```

The first goal is portability and reproducibility, not production throughput.

## Repository baseline

Agentic Sheets commit validated on DGX Spark:

```text
de6d678c23a880df9bf85738e8b4a0c917d0d85b
```

The working tree was clean at clone time.

## DGX Spark host baseline

Observed on 2026-08-07:

```text
Architecture:       aarch64
Operating system:   Ubuntu 24.04.4 LTS
Kernel:             6.17.0-1029-nvidia
Docker Engine:      29.2.1
Docker Compose:     5.0.2
CPU count:          20
System memory:      121.7 GiB
GPU:                NVIDIA GB10
NVIDIA driver:      580.173.02
CUDA reported:      13.0
Root filesystem:    3.7 TiB
Available disk:     ~3.5 TiB
```

Docker discovered the NVIDIA GPU through CDI, including
`nvidia.com/gpu=0` and `nvidia.com/gpu=all`.

## GPU access from Docker

The GPU was successfully exposed to a normal Docker container:

```bash
docker run --rm   --gpus all   ubuntu   nvidia-smi
```

The container reported the NVIDIA GB10 using driver `580.173.02`.

Result: **PASS**

## ARM64 dependency check

The base Agentic Sheets Compose stack uses:

```text
agentic-sheets-backend
agentic-sheets-sheets-mcp
postgres:16
```

The relevant upstream build/runtime images publish native ARM64 variants:

- `postgres:16`
- `maven:3.9-eclipse-temurin-21`
- `eclipse-temurin:21-jre-jammy`

This allowed the Java services to be built directly on the DGX Spark rather
than running through x86 emulation.

## Native ARM64 build

The two local application images were built on DGX Spark:

```text
agentic-sheets-backend     arm64/linux
agentic-sheets-sheets-mcp  arm64/linux
```

The clean build completed successfully.

Result: **PASS**

## Base stack startup

The ordinary Compose stack was started without a local-LLM overlay:

```bash
export POSTGRES_PASSWORD='agentic-sheets-local'

docker compose up -d --wait
```

All services became healthy:

```text
postgres     Healthy
sheets-mcp   Healthy
backend      Healthy
```

The application health endpoint returned:

```json
{
  "groups": [
    "liveness",
    "readiness"
  ],
  "status": "UP"
}
```

Result: **PASS**

## Current conclusion

Agentic Sheets is ARM64-portable on DGX Spark at the application and core
container dependency level.

No ARM64-specific change was required in the backend or Sheets MCP source.

This clears the application-portability phase and allows inference-runtime
testing to be isolated from application-runtime issues.

## Next phase: exact local-LLM baseline

The first GPU inference experiment should preserve the previous CPU experiment
as closely as possible.

Previous baseline model:

```text
Qwen 2.5 3B Instruct
GGUF quantization: Q4_K_M
temperature: 0
context size: 4096
```

Because the earlier Docker Model Runner experiment used GGUF through
llama.cpp, the first DGX Spark experiment will use CUDA-enabled llama.cpp with
the same Qwen 2.5 3B Instruct Q4_K_M checkpoint.

This keeps the comparison focused on the inference hardware/runtime rather
than changing model family or quantization at the same time.

### Build llama.cpp for GB10

NVIDIA's DGX Spark llama.cpp playbook currently recommends building llama.cpp
with CUDA and GB10's `sm_121` architecture:

```bash
sudo apt update
sudo apt install -y git clang cmake libcurl4-openssl-dev libssl-dev

git clone https://github.com/ggml-org/llama.cpp ~/llama.cpp
cd ~/llama.cpp

cmake -B build   -DGGML_NATIVE=ON   -DGGML_CUDA=ON   -DGGML_CURL=ON   -DGGML_RPC=ON   -DCMAKE_CUDA_ARCHITECTURES=121a-real

cmake --build build   --config Release   --target llama-server   -j
```

Reference:
https://build.nvidia.com/spark/llama-cpp

### Start the baseline model

Run llama-server on port `30000` and expose an API model alias matching the
existing Agentic Sheets configuration:

```bash
cd ~/llama.cpp

./build/bin/llama-server   -hf Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M   --alias 'qwen2.5:3B-Q4_K_M'   --host 0.0.0.0   --port 30000   --ctx-size 4096   --n-gpu-layers 999   --temp 0   --top-k 1
```

The official Qwen GGUF repository exposes the Q4_K_M checkpoint for llama.cpp:

https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF

### Verify the inference server

```bash
curl -fsS http://localhost:30000/v1/models | jq

curl -fsS   http://localhost:30000/v1/chat/completions   -H 'Content-Type: application/json'   -d '{
    "model": "qwen2.5:3B-Q4_K_M",
    "messages": [
      {
        "role": "user",
        "content": "Return JSON only: {\"status\":\"ok\"}"
      }
    ],
    "temperature": 0
  }' | jq
```

During the request, observe GPU activity in a second terminal:

```bash
watch -n 0.5 nvidia-smi
```

Also preserve llama.cpp startup and timing logs.

## Agentic Sheets Spark overlay

Use `compose.spark-llama.yaml` from this update to point the backend at the
host llama.cpp server while preserving the same Spring AI abstraction.

Start with:

```bash
docker compose   -f compose.yaml   -f compose.spark-llama.yaml   up -d --build --wait
```

Verify:

```bash
docker compose   -f compose.yaml   -f compose.spark-llama.yaml   exec backend sh -c '
    echo "Endpoint: ${SPRING_AI_OPENAI_BASE_URL:-missing}"
    echo "Model: ${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-missing}"
    echo "Timeout: ${SPRING_AI_OPENAI_TIMEOUT:-missing}"
  '
```

Expected:

```text
Endpoint: http://host.docker.internal:30000/v1
Model: qwen2.5:3B-Q4_K_M
Timeout: PT5M
```

## Benchmark sequence

Run the same logical benchmarks used on the CPU systems.

### 1. Simple mapping

Use the existing simple mapping prompt/script, changing only the inference
endpoint where necessary.

Record:

- cold/load elapsed time
- three warm elapsed times
- prompt processing rate
- generation rate
- total response time
- output correctness

### 2. Full holdings proposal

Run the same synthetic holdings fixture:

```text
holdings_jpmc_20260115.xlsx
worksheet: Holdings
canonical model: Holdings
client: jpmc
```

Record:

- HTTP result
- total elapsed time
- structured decoding result
- correct source-column mappings
- hallucinated columns
- sum-type resolution
- structural validation result

Previous Qwen 2.5 3B CPU result:

```text
Structured decoding: PASS
Source-column mapping: 11/11
Hallucinated columns: none
Sum-type completion: 0/2
Structural validation: FAIL
```

The key question is whether the same model reproduces the same quality result
while completing substantially faster on GB10.

## Follow-up: inference-provider portability

After the exact llama.cpp baseline is recorded, add a separate vLLM
experiment.

NVIDIA provides a DGX Spark vLLM container path that exposes the standard
OpenAI-compatible API on port `8000`.

Reference:
https://build.nvidia.com/spark/vllm

Do not combine the vLLM result with the exact GGUF baseline. Treat it as a
separate experiment because model format, quantization, and runtime may differ.

Candidate progression:

```text
Qwen 2.5 3B baseline
-> stronger 7B/14B model
-> 32B model
-> larger model only if smaller models still fail deterministic validation
```

For each model, preserve the same synthetic fixture and deterministic
validator.

## Evaluation principle

The LLM proposes semantic mappings.

Deterministic application code remains responsible for validating whether a
proposal satisfies the canonical model contract.

The structural validator is not weakened to accommodate a model.

## Status

```text
DGX Spark host validation:           PASS
Docker GPU visibility:              PASS
Agentic Sheets native ARM64 build:  PASS
Base Compose startup:               PASS
Application health:                 PASS
llama.cpp CUDA baseline:            NEXT
vLLM provider test:                 PLANNED
stronger-model evaluation:          PLANNED
```
