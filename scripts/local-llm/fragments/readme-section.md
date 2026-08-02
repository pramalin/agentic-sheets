## Local LLM evaluation

Agentic Sheets can run mapping proposals through Docker Model Runner using its
OpenAI-compatible local endpoint. The local model replaces only hosted
inference; structural validation, human approval, canonical conversion, and
delivery remain deterministic application responsibilities.

The current reference setup uses Qwen 2.5 3B with llama.cpp. See
[Local LLM Evaluation](docs/local-llm-evaluation.md) for configuration,
reproduction commands, measured CPU-only results, known limitations, and the
GPU comparison plan.
