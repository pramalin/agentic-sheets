# Local LLM Hardware and GPU Comparison

Status: completed compatibility investigation; GPU benchmark not achieved  
Evaluation date: 2026-08-02

## Goal

Compare the existing CPU-only Chromebook result with the same Agentic Sheets
commit, model, prompt, fixture, and Docker Model Runner configuration on a
gaming laptop equipped with an NVIDIA GPU.

The controlled comparison was intended to change only the hardware execution
path. Model quality and latency were both retained as measured outcomes.

## Fair-comparison controls

The following remained fixed:

- Agentic Sheets Git commit
- Docker Model Runner backend: llama.cpp
- Model tag: `qwen2.5:3B-Q4_K_M`
- Model ID prefix: `41045df49cc0`
- `compose.local-llm.yaml`
- Context size: 4096
- Runtime threads: 4
- Batch threads: 4
- Temperature: 0
- Top-k: 1
- Prompt and structured-output schema
- Spreadsheet fixture and worksheet

Changing the inference image or CUDA major version would create a separate
compatibility experiment rather than a hardware-only comparison.

## Gaming-laptop environment

| Component | Observed value |
|---|---|
| Docker Engine | 29.2.0 |
| Docker Compose | 5.2.0 |
| Docker Model Runner | 1.2.6 |
| Host kernel | `6.8.0-136-generic` |
| GPU | NVIDIA GeForce GTX 1050 Ti |
| VRAM | 4096 MiB |
| NVIDIA driver | 580.173.02 |
| Driver-reported CUDA API | 13.0 |
| DMR backend | llama.cpp |
| Model | `qwen2.5:3B-Q4_K_M` |
| Available container memory | 23.34 GiB |

## Initial run

The Agentic Sheets stack started successfully, all services became healthy, and
the local-model configuration passed verification.

The first simple model test returned correct JSON but took 44.13 seconds. The
full holdings proposal took 300.98 seconds and reproduced the same deterministic
422 rejection observed on the Chromebook:

- `asset_class` had neither `selectedVariant` nor `variantValueMap`
- `currency` had neither `selectedVariant` nor `variantValueMap`

The initial simple run showed approximately 250-390% CPU and about 1.88 GiB of
Model Runner memory. No GPU measurement was captured at this stage.

## NVIDIA driver repair

The initial host check failed with:

```text
Failed to initialize NVML: Driver/library version mismatch
NVML library version: 580.173
```

A reboot resolved the mismatch. After reboot:

```text
NVIDIA-SMI:      580.173.02
Driver version:  580.173.02
Loaded module:   580.173.02
Installed module: 580.173.02
GPU:             NVIDIA GeForce GTX 1050 Ti
VRAM:            4096 MiB
```

Docker GPU passthrough was then configured and verified:

```bash
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker

docker run --rm \
  --runtime=nvidia \
  --gpus all \
  ubuntu \
  nvidia-smi
```

The container displayed the same GTX 1050 Ti and driver information. This
confirmed that the host driver and NVIDIA Container Toolkit path were working.

## Docker Model Runner CUDA attempt

Docker Model Runner was reinstalled explicitly for CUDA:

```bash
docker model reinstall-runner \
  --backend llama.cpp \
  --gpu cuda
```

The command pulled `docker/model-runner:latest-cuda` and restarted the runner.
However, its log reported:

```text
installed llama-server gpuSupport=false
```

The post-reboot measurements continued to look like CPU inference:

| Run | Elapsed | Observed CPU | Model Runner memory | Result |
|---|---:|---:|---:|---|
| Simple, model load included | 28.62 s | up to about 299% | about 3.37 GiB | correct JSON |
| Simple, warm | 15.44 s | about 270-298% | about 3.37 GiB | correct JSON |

No meaningful GPU utilization or VRAM allocation was established. These are
therefore CPU-fallback measurements, not GPU measurements.

## Compatibility explanation

The NVIDIA GeForce GTX 1050 Ti belongs to the GTX 10 Series, which NVIDIA
identifies as Pascal architecture.

Docker Model Runner's current open-source build configuration maps its CUDA
llama.cpp variant to:

```text
ghcr.io/ggml-org/llama.cpp:server-cuda13
```

NVIDIA's CUDA Toolkit 13.0 release notes state that offline compilation and
library support for Maxwell, Pascal, and Volta were removed in the CUDA 13 major
release. Applications for those architectures must continue to be built with a
CUDA 12.x toolkit.

The observed behavior is therefore consistent with the software stack:

```text
GTX 1050 Ti (Pascal)
    + Docker Model Runner CUDA variant
    + llama.cpp server built for CUDA 13
    -> gpuSupport=false
    -> CPU fallback
```

This does not mean the GTX 1050 Ti is incapable of CUDA inference. The upstream
llama.cpp project publishes a separate `server-cuda` image built with CUDA 12,
which is the relevant toolchain generation for Pascal. Using that image would
require bypassing or customizing the current Docker Model Runner path and must
be recorded as a separate experiment.

## Comparison results

| Metric | Chromebook CPU | Gaming laptop before repair | Gaming laptop after repair |
|---|---:|---:|---:|
| Execution path | CPU | CPU | CPU fallback |
| Simple run with model load | 20.20 s bundled reproduction | 44.13 s first run | 28.62 s |
| Warm simple run | 8.39 s best recorded | not measured | 15.44 s |
| Full holdings proposal | 190.84 s bundled reproduction | 300.98 s | not rerun |
| Correct source mappings | 11/11 | 11/11 | simple fixture correct |
| Sum-type completion | 0/2 | 0/2 | not exercised in full workflow |
| Structural validation | fail as designed | fail as designed | not rerun |
| GPU acceleration | not applicable | not active | not active |

The first gaming-laptop image build took approximately 194 seconds because it
included image pulls, Maven dependency retrieval, and compilation. That build
time is not part of inference performance.

## Decision

1. Keep the checked-in Docker Model Runner configuration unchanged.
2. Record the GTX 1050 Ti attempt as a compatibility finding, not as a failed
   application integration.
3. Do not label the gaming-laptop numbers as GPU performance.
4. Use a Turing-or-newer NVIDIA GPU for a controlled comparison with the current
   CUDA 13 Docker Model Runner path.
5. Treat a direct llama.cpp CUDA 12 test on the GTX 1050 Ti as an optional,
   separate experiment with a changed inference stack.

The application integration remains valid: Agentic Sheets reached Docker Model
Runner, obtained structured responses, and applied deterministic validation.
The limitation occurred below the application boundary, where the selected
CUDA inference image did not support the GPU architecture.

## Commands for a future compatible-GPU run

Verify host and Docker GPU access:

```bash
nvidia-smi

docker run --rm \
  --runtime=nvidia \
  --gpus all \
  ubuntu \
  nvidia-smi
```

Install the CUDA runner:

```bash
docker model reinstall-runner \
  --backend llama.cpp \
  --gpu cuda
```

Confirm actual acceleration before benchmarking:

```bash
docker model logs 2>&1 \
  | grep -Ei 'cuda|gpu|offload|layers|gpuSupport|no usable GPU' \
  | tail -100
```

Monitor the device while running the test:

```bash
watch -n 0.5 nvidia-smi
```

Run one model-load test and three warm tests:

```bash
docker model unload qwen2.5:3B-Q4_K_M || true
./scripts/local-llm/profile-model.sh

for run in 1 2 3; do
  echo "Warm simple run $run"
  ./scripts/local-llm/profile-model.sh
done
```

Only record the result as a GPU benchmark after Model Runner appears in
`nvidia-smi`, consumes VRAM, and reports an active CUDA/offload path.

## References

- [Docker Model Runner](https://docs.docker.com/ai/model-runner/)
- [Docker Model Runner inference engines](https://docs.docker.com/ai/model-runner/inference-engines/)
- [Docker Model Runner source: CUDA variant maps to llama.cpp `server-cuda13`](https://github.com/docker/model-runner)
- [NVIDIA CUDA Toolkit 13.0 release notes](https://docs.nvidia.com/cuda/archive/13.0.0/cuda-toolkit-release-notes/index.html)
- [NVIDIA GeForce architecture comparison](https://www.nvidia.com/en-us/geforce/graphics-cards/compare/)
- [llama.cpp Docker images](https://github.com/ggml-org/llama.cpp/blob/master/docs/docker.md)
