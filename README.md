# agentic-sheets

An agentic pipeline that reads client spreadsheets in varying, drifting
formats, infers a mapping onto a fixed canonical model, puts a human in
the loop to confirm it, and hands the approved result off to whichever
team owns that data — without ever writing to a database itself.

Built step by step, in the same documented-as-we-go style as
[pramalin/agentic-analytics](https://github.com/pramalin/agentic-analytics)
and [pramalin/sheets-reader-mcp](https://github.com/pramalin/sheets-reader-mcp),
which this project depends on directly for spreadsheet access
(`list_worksheets`, `describe_table`, `read_rows` over MCP).

## Design principles

These came out of a fairly long design conversation, not a spec handed
down up front — worth stating explicitly since they shape every step
below.

- **The agent proposes; it never writes.** After human approval, a
  deterministic validator checks the result against the canonical ADT and
  a dispatcher sends it on — no LLM-authored SQL, no LLM-invoked write
  tool, ever.
- **The system does not know any team's database schema, and never
  touches one.** Each team runs its own service and owns its own
  persistence; `agentic-sheets` only needs to know where to send an
  approved payload and how to call it (REST or MCP, per that team's
  config).
- **Canonical models are Algebraic Data Types — product types (records)
  and sum types (tagged variants) — not a flat field list.** The system
  defines this format once; every team supplies a configuration in it,
  not code. See `canonical-models/SCHEMA.md`.
- **One config, three uses.** A canonical model's ADT is simultaneously
  the mapping target, the structured-output schema the agent is bound to,
  and the wire contract the team's service receives.
- **Configuration is parsed exactly once, into a typed object, by exactly
  one component.** This is a direct response to a real failure mode:
  configuration that's "not constant at all," ending up treated as a raw
  string and re-parsed ad hoc in multiple places. See `mapping-notes.md`
  for the fuller story and `canonical-models/SCHEMA.md`'s "Loading &
  reload" section for the resulting design (atomic, fail-safe reload;
  `version` pinned on in-flight proposals and the mapping-memory cache).

## Repository layout

```
docker-compose.yaml         Postgres (Step 1); backend/frontend services arrive later
.env.example                 Postgres credentials -- copy to .env
canonical-models/
  SCHEMA.md                  The ADT configuration format itself
  holdings.yaml               Canonical model: holdings/positions
  market_rate_book_value.yaml Canonical model: market rate + book value
  runtime-sketch/
    CanonicalType.java        Illustrative JVM representation (Step 4 implements this for real)
client-configs/
  jpmc.yaml, metlife.yaml,
  pimco.yaml                  Per-client source conventions (date format, etc.) -- see SCHEMA.md
sample-input/                 Fixture spreadsheets exercising real-world variation:
                               banner rows, renamed columns, unmapped extra columns,
                               missing fields, non-native date formats
sample-canonical/             Expected canonical output for the fixtures above --
                               doubles as a future automated test fixture
mapping-notes.md              The reasoning behind every mapping decision above,
                               written the way a human reviewer would see it
```

## Prerequisites

- Docker Engine + Docker Compose

## Getting started

```bash
cp .env.example .env
docker compose up -d
docker compose ps   # postgres should report healthy
```

## Roadmap

- [x] **Step 1** — Empty repo, `docker-compose.yaml` (Postgres only),
      README with this roadmap. *(this commit)*
- [ ] **Step 2** — Spring Boot backend skeleton (health endpoint).
      Boot 4.1.0 + Spring AI 2.0.0, matching `sheets-reader-mcp`'s stack.
- [ ] **Step 3** — Wire the backend into `docker-compose.yaml`; verify
      `docker compose up` end to end.
- [ ] **Step 4** — `CanonicalModelRegistry`: parse `canonical-models/*.yaml`
      and `client-configs/*.yaml` into typed, validated objects (atomic,
      fail-safe reload). Orchestration Postgres schema via a plain SQL
      init script: `import_batch`, `mapping_proposal`, `mapping_memory`,
      `delivery_log`. No canonical *target* tables here — those belong to
      each team's own service.
- [ ] **Step 5** — Spring AI MCP client wired to `sheets-reader-mcp`.
      First capability: explore a spreadsheet
      (`list_worksheets`/`describe_table`/`read_rows`) — no mapping logic
      yet, just prove the connection.
- [ ] **Step 6** — Column-mapping inference: agent output bound to a
      canonical model's ADT via structured output, scored against
      `client-configs` and the field synonyms in each canonical model.
      Persisted as a `mapping_proposal`. Not committed anywhere.
- [ ] **Step 7** — Deterministic validator + dispatcher: validate an
      approved proposal against its ADT, serialize it, and call the
      team's configured `target` (REST or MCP, per `transport`), with
      retry/rejection classification per `target.delivery`. Outcome
      recorded in `delivery_log`.
- [ ] **Step 8** — Review web UI (Angular) — queue, review screen
      (source columns + samples + proposed field + confidence, editable),
      approve/edit/reject, plus a delivery-status view for Step 7's
      outcomes.
- [ ] **Step 9** — Inbox scanner: scheduled poll, content-hash dedupe
      (same filename + same hash → skip; same filename + different hash
      → new batch), filename parsing
      (`<feedType>_<client>_<yyyyMMdd>` → canonical model + client id),
      auto-creating batches that feed Steps 5–8.
- [ ] **Step 10** — Mapping memory: fingerprint a source file's column
      layout together with the canonical model's `version`; skip the
      agent entirely when a fingerprint matches a previously approved
      mapping, and only invoke it on genuine drift.
- [ ] **Step 11 (stretch)** — `mcp-gateway` in front of `sheets-reader-mcp`,
      only once there's an actual second MCP server or an
      environment-scoped exposure need — not before.

## Project references

- `sheets-reader-mcp`: https://github.com/pramalin/sheets-reader-mcp
- `agentic-analytics` (the project this one is modeled after):
  https://github.com/pramalin/agentic-analytics
