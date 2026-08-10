# LinkedIn draft — Agentic Sheets on DGX Spark

A useful milestone in my personal AI engineering portfolio today: I validated
that **Agentic Sheets runs natively on NVIDIA DGX Spark / ARM64**.

Agentic Sheets is an experiment in governed spreadsheet onboarding: an LLM
proposes mappings from source spreadsheets into canonical models, while
deterministic validation and human approval remain responsible for correctness.

I wanted to know whether the application itself would move cleanly from my
existing x86 development environment to DGX Spark before changing the
inference runtime.

So I tested the layers independently.

✅ DGX Spark detected as `aarch64`  
✅ NVIDIA GB10 visible from Docker  
✅ PostgreSQL 16 has a native ARM64 image  
✅ Maven + Eclipse Temurin Java 21 build/runtime images have ARM64 variants  
✅ Agentic Sheets backend built natively as `arm64/linux`  
✅ Sheets MCP built natively as `arm64/linux`  
✅ PostgreSQL, MCP, and backend all started healthy  
✅ Spring Boot actuator reported `UP`

No ARM64-specific application code change was required.

The next experiment is intentionally controlled: run the same Qwen 2.5 3B
Q4_K_M model used in my earlier CPU tests through CUDA-enabled llama.cpp on
the GB10, expose it through an OpenAI-compatible API, and run the exact same
synthetic holdings fixture and deterministic validator.

After establishing that baseline, I plan to compare vLLM and stronger models
to answer two different questions:

1. How much does the hardware/runtime improve inference latency?
2. What is the smallest local model that reliably satisfies the application's
   structured mapping contract?

For me, that separation is important. Faster inference is useful, but an AI
application still needs deterministic boundaries around what the model is
allowed to propose.

Repository:
https://github.com/pramalin/agentic-sheets

#AIEngineering #LocalLLM #DGXSpark #SpringAI #Java #Docker #MCP
