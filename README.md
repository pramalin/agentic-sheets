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

![agentic-sheets architecture: absorb client spreadsheet drift without micro-releases — a changing client edge is inspected through a read-only MCP server, a mapping agent proposes a mapping for human review, and deterministic code constructs, validates, and dispatches canonical rows to a stable team-owned service](docs/images/agentic-sheets-drift-resilient-architecture.svg)

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
- **One config, three roles — but the agent's raw output isn't ADT-bound
  yet.** A canonical model's ADT is the mapping target shown to the
  agent as prompt text, the contract Step 7's deterministic validator
  will check an *approved* proposal against, and the wire format the
  team's service receives. What it is *not*, currently, is the
  structured-output schema itself: Spring AI binds the response to the
  fixed `MappingProposal` Java record, not to a schema generated from
  the runtime canonical model — there's no compile-time Java type for
  "a Holdings row" to bind to, since canonical models load from YAML at
  runtime. `MappingProposal` is deliberately generic instead: a
  flattened list of `(canonicalFieldPath, source, confidence, notes)`
  entries, the same shape regardless of which model is being mapped.
  `MappingProposalStructuralValidator` checks that output against the
  actual ADT immediately after decoding, before persistence — catching
  a nonexistent field path, an invented source column, or an invalid
  variant name — but this is a lighter check than a fully
  ADT-constrained response schema would be, and Step 7's validator is
  where real enforcement against constructed canonical *rows* happens.
  An external review of Step 6 caught the original wording here
  ("the agent is bound to the ADT") overclaiming this; see
  `mapping-notes.md`'s "Step 6.1 hardening" section for the fuller story.
- **A sum type's variant can be resolved two different ways, and the
  agent has to pick the right one, not default to either.** Confirmed
  live in Step 6: `selectedVariant` when a whole file is one fixed
  variant (e.g. every row is `USD`); `variantValueMap` when the variant
  genuinely depends on each row's own data (e.g. a column with both
  `Equity` and `Fixed Income` rows). Getting this wrong looked, at
  first, like a formatting quirk (an unresolved empty `selectedVariant`)
  rather than the real structural gap it was — see `mapping-notes.md`.
- **Configuration is parsed exactly once, into a typed object, by exactly
  one component.** This is a direct response to a real failure mode:
  configuration that's "not constant at all," ending up treated as a raw
  string and re-parsed ad hoc in multiple places. See `mapping-notes.md`
  for the fuller story and `canonical-models/SCHEMA.md`'s "Loading &
  reload" section for the resulting design (atomic, fail-safe reload;
  `version` pinned on in-flight proposals and the mapping-memory cache).

## Repository layout

```
compose.yaml         Postgres, sheets-mcp, backend; no frontend service yet -- `npm run dev` is how Step 8b's UI runs for now (see frontend/README.md), a real Docker/nginx service is deferred until the UI itself is further along
frontend/                     Step 8b's review UI (React + TypeScript + Vite) -- see frontend/README.md
docs/images/                  Architecture diagram(s) referenced from this README
.env.example                 Postgres credentials -- copy to .env
db/init/                      Plain SQL orchestration schema (Step 4)
backend/
  pom.xml, Dockerfile
  src/main/java/com/alai/agenticsheets/
    canonical/                CanonicalModelRegistry and the ADT type model (Step 4)
    spreadsheet/               MCP client wiring to sheets-reader-mcp (Step 5)
    mapping/                   Mapping agent (Step 6); deterministic ADT validation
                                (CanonicalRowBuilder) and dispatch (Dispatcher) (Step 7)
canonical-models/
  SCHEMA.md                  The ADT configuration format itself
  holdings.yaml               Canonical model: holdings/positions
  market_rate_book_value.yaml Canonical model: market rate + book value
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
ui-notes.md                   Review UI design decisions -- framework choice, auth,
                               kept separate from mapping-notes.md as a different concern
```

## Prerequisites

- Docker Engine + Docker Compose. As of Step 7.5, this is also needed
  to run `mvn test` in `backend/` — one test uses Testcontainers to spin
  up a real Postgres container (see `mapping-notes.md`'s Step 7.5
  follow-up section). Previously `mvn test` needed no live dependency
  at all; that's no longer true for this one test.
- [`sheets-reader-mcp`](https://github.com/pramalin/sheets-reader-mcp)
  checked out as a sibling directory (e.g. `~/sources/agentic-sheets` and
  `~/sources/sheets-reader-mcp`) -- `compose.yaml`'s `sheets-mcp` service
  builds it via a relative path. Point that path at wherever you actually
  keep it if your layout differs.
- An OpenAI API key, for Step 6's mapping agent (`OPENAI_API_KEY` in
  `.env`). Everything else in this project runs fine without one --
  `/internal/mapping/propose` is the only thing that needs it.
- Every `/internal/**` endpoint (except `/internal/fake-target/**`)
  requires `Authorization: Bearer <AGENTIC_SHEETS_API_KEY>` as of Step 8
  -- `.env.example` provides a real local-dev value
  (`dev-local-secret`) so this works out of the box; change it before
  running anywhere that isn't your own machine.

## Getting started

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps                                # postgres, sheets-mcp, and backend should all report healthy
curl -f http://localhost:8081/actuator/health     # {"status":"UP", ..., "db":{"status":"UP"}} -- no auth needed

# Every /internal/** call below needs this header -- matches
# AGENTIC_SHEETS_API_KEY's .env.example default.
AUTH='Authorization: Bearer dev-local-secret'

curl -s -H "$AUTH" http://localhost:8081/internal/canonical/models | jq   # both canonical models loaded
curl -s -H "$AUTH" http://localhost:8081/internal/canonical/clients | jq  # jpmc, metlife, pimco

# Step 5: MCP client wiring to sheets-reader-mcp
curl -s -H "$AUTH" http://localhost:8081/internal/explore/tools | jq
curl -s -H "$AUTH" "http://localhost:8081/internal/explore/worksheets?path=holdings_jpmc_20260115.xlsx" | jq
curl -s -H "$AUTH" "http://localhost:8081/internal/explore/table?path=holdings_jpmc_20260115.xlsx&worksheet=Holdings" | jq
curl -s -H "$AUTH" "http://localhost:8081/internal/explore/rows?path=holdings_jpmc_20260115.xlsx&worksheet=Holdings&offset=0&limit=2" | jq

# Step 6: mapping agent (needs OPENAI_API_KEY set in .env)
curl -s -H "$AUTH" -X POST "http://localhost:8081/internal/mapping/propose?modelId=Holdings&clientId=jpmc&path=holdings_jpmc_20260115.xlsx&worksheet=Holdings" | jq

# Step 7: approve the proposal returned above (use its mappingProposalId),
# then validate + dispatch to the local fake-target
curl -s -H "$AUTH" -X POST "http://localhost:8081/internal/mapping/proposals/1/approve" | jq

# Step 7.1: if delivery failed transiently, retry without re-approving
curl -s -H "$AUTH" -X POST "http://localhost:8081/internal/mapping/proposals/1/redeliver" | jq

# Step 8: the review queue, a proposal's full history, and rejecting one
curl -s -H "$AUTH" "http://localhost:8081/internal/mapping/proposals?status=PENDING" | jq
curl -s -H "$AUTH" "http://localhost:8081/internal/mapping/proposals/1" | jq
curl -s -H "$AUTH" -X POST "http://localhost:8081/internal/mapping/proposals/2/reject?reason=wrong+currency+mapping" | jq
```

If you already ran `docker compose up` before this change, the `postgres-data`
volume already exists and initialized *without* the current orchestration
schema -- Postgres only runs `docker-entrypoint-initdb.d` scripts against
a brand new, empty data directory. This applies again as of Step 7.3's
new `uq_mapping_proposal_active_batch` partial unique index -- either
`docker compose down -v` first (destroys the volume, re-initializes
cleanly; there's nothing in it worth keeping at this stage) or apply the
current `db/init/01-orchestration-schema.sql` to the running container
by hand.

### Running the review UI (Step 8b)

```bash
cd frontend
npm install
npm run dev
```

Needs the backend already running (above). Open the URL Vite prints
(typically `http://localhost:5173`), enter the same value as
`AGENTIC_SHEETS_API_KEY` when prompted. Every piece of the original
Step 8 scope works end to end: the queue, the review screen with
source-column samples shown inline, approve/edit/reject, and
validation/delivery history — see `frontend/README.md` for exact
status.

## API endpoints (internal, for now)

Every `/internal/**` endpoint except `/internal/fake-target/**` requires
`Authorization: Bearer <AGENTIC_SHEETS_API_KEY>` as of Step 8 (see
`ApiKeyAuthFilter`) -- a single shared secret appropriate for one
organization's integrated UI, not the multi-tenant/multiple-identity-
provider auth an embeddable product would need (see `ui-notes.md`).
Still not a public API in any other sense -- no `/api` versioning, no
per-user permissions. `compose.yaml` publishes these to host ports
(8081, 5432) for manual verification, which also means they're reachable
from the host and potentially other machines depending on your Docker
and firewall configuration -- don't expose these ports on an untrusted
network, and don't ship the `.env.example` default secret anywhere real.

| Endpoint | Method | Purpose |
|---|---|---|
| `/actuator/health` | GET | Overall + `db` health -- no auth required |
| `/internal/canonical/models` | GET | Canonical models currently loaded |
| `/internal/canonical/clients` | GET | Client source-conventions currently loaded |
| `/internal/explore/tools` | GET | Tools `sheets-mcp` exposes (proves the MCP connection) |
| `/internal/explore/worksheets?path=` | GET | `list_worksheets` on a file in the mounted workspace |
| `/internal/explore/table?path=&worksheet=` | GET | `describe_table` — headers, inferred types, samples |
| `/internal/explore/rows?path=&worksheet=&offset=&limit=` | GET | `read_rows` — paginated row data |
| `/internal/mapping/propose?modelId=&clientId=&path=&worksheet=` | POST | Runs the mapping agent; creates/reuses an `import_batch`, persists a `mapping_proposal` |
| `/internal/mapping/proposals?status=&limit=` | GET | The review queue — most recent first, optionally filtered by status (repeat `status=` for several at once, e.g. the UI's "Needs attention" filter). Joined with batch context (client, file, worksheet) as of Step 8b, since a bare proposal ID isn't enough to build a usable queue view from |
| `/internal/mapping/proposals/{id}` | GET | One proposal's full detail: the proposal, its batch, every validation run, every delivery attempt |
| `/internal/mapping/proposals/{id}/approve?reviewedBy=` | POST | Approves a pending proposal (atomically claimed), validates it against the ADT, dispatches valid rows |
| `/internal/mapping/proposals/{id}/reject?reviewedBy=&reason=` | POST | Rejects a pending proposal (atomically claimed) |
| `/internal/mapping/proposals/{id}/redeliver` | POST | Re-runs validation + dispatch for an already-approved proposal -- for retrying after a transient failure |
| `/internal/mapping/proposals/{id}/amend` | POST | The "edit" verb — supersedes a pending proposal with a human-corrected one, validated the same way agent output is |
| `/internal/mapping/batches/{id}/recover-stuck` | POST | **Break-glass only** — manually recovers a batch stuck in `PROCESSING` or `PROPOSING`; only safe after confirming the previous process is actually gone, never for a merely-slow request |
| `/internal/fake-target/{service}` | POST | Local-testing-only stand-in for a team's receiving service (no auth -- see `FakeTargetController`) |

## Roadmap

- [x] **Step 1** — Empty repo, `compose.yaml` (Postgres only),
      README with this roadmap.
- [x] **Step 2** — Spring Boot backend skeleton (health endpoint).
      Boot 4.1.0 + Spring AI 2.0.0, matching `sheets-reader-mcp`'s stack.
- [x] **Step 3** — Wire the backend into `compose.yaml`; verify
      `docker compose up` end to end.
- [x] **Step 4** — `CanonicalModelRegistry`: parse `canonical-models/*.yaml`
      and `client-configs/*.yaml` into typed, validated objects (atomic,
      fail-safe reload). Orchestration Postgres schema via a plain SQL
      init script: `import_batch`, `mapping_proposal`, `mapping_memory`,
      `delivery_log`. No canonical *target* tables here — those belong to
      each team's own service.
- [x] **Step 5** — Spring AI MCP client wired to `sheets-reader-mcp`.
      First capability: explore a spreadsheet
      (`list_worksheets`/`describe_table`/`read_rows`) — no mapping logic
      yet, just prove the connection.
- [x] **Step 6** — Column-mapping inference: render the selected
      canonical ADT and client conventions into the agent's prompt; bind
      the response to the generic `MappingProposal` structured-output
      type (not a schema derived from the ADT — see the design
      principles above); check it against the actual ADT with
      `MappingProposalStructuralValidator` before persisting. Persisted
      as a `mapping_proposal`. No canonical-row validation or delivery
      yet — that's Step 7.
- [x] **Step 7** — Deterministic validator + dispatcher: given an
      approved proposal, construct each source row into an actual
      `CanonicalValue` and validate it against the ADT
      (`CanonicalRowBuilder`), serialize the rows that pass, and call
      the team's configured `target`. Only `transport: rest` with
      `auth.type: api-key` is actually implemented — `mcp` transport and
      `oauth2-client-credentials`/`mtls` auth parse and validate
      correctly but dispatch fails fast with a clear not-yet-implemented
      error rather than a guessed-at flow. Retry/rejection
      classification per `target.delivery`, outcome recorded in
      `delivery_log`. No review UI yet — approval is a plain endpoint
      call (`/proposals/{id}/approve`), same "manual now, automatic
      later" pattern as Step 6.
- [x] **Step 7.1** — Approval/delivery reliability hardening, after a
      second external review: atomic proposal claiming
      (compare-and-set, closing a real double-delivery race), a new
      `/proposals/{id}/redeliver` endpoint so a failure mid-flight
      doesn't leave things permanently stuck, source-file re-verification
      at approval, a typed `scale` transformation (closing a real silent
      data-corruption risk — `conversionNotes` was never actually
      executed), retry classification that actually honors
      `retryableStatusCodes` (it previously didn't), HTTP timeouts,
      missing-secret fail-fast, and `delivery_log` proposal-level
      provenance. See `mapping-notes.md` for what was fixed, what was
      explicitly declined (one review suggestion would have broken an
      already-tested, deliberate design choice), and what's still
      deferred (client-config version-pinning, an all-or-nothing vs.
      valid-rows-only delivery policy, durable validation-report
      storage).
- [x] **Step 7.2** — A third external review found that Step 7.1's own
      new `/redeliver` endpoint had a real concurrency gap of its own:
      it checked the proposal was `APPROVED` (permanent, never changes
      back) but never atomically claimed the *batch*, so two concurrent
      `/redeliver` calls -- or one racing `/approve`'s own in-flight
      delivery -- could both dispatch. Fixed with a second atomic claim
      on `import_batch` (`ImportBatchRepository.claimForProcessing`),
      shared by `/approve` and `/redeliver`, gated by different eligible
      starting statuses for each. Also added: distinct `SOURCE_CHANGED`/
      `CONFIG_CHANGED` batch statuses so a drift failure doesn't leave
      the batch looking like a plain, misleadingly-successful `APPROVED`;
      a second source-hash check after all rows are read, narrowing (not
      closing) a real time-of-check/time-of-use window; `DeliveryConfig`
      invariant validation (a malformed `delivery:` block now fails at
      config-load time, not at actual delivery time); and a stable
      idempotency key sent with every delivery. See `mapping-notes.md`
      for the full account, including what this review confirmed was
      already correct from the previous round.
- [x] **Step 7.3** — A fourth external review caught that Step 7.2's own
      fix introduced a regression: the atomic batch claim moved to the
      top of `processDelivery`, but the try/catch didn't move with it,
      leaving the canonical-model lookup, the pre-read hash check, and
      the client-config lookup unprotected -- any exception there left
      a batch stuck in `PROCESSING` with no eligible status set (for
      either `/approve` or `/redeliver`) able to reclaim it. Fixed by
      wrapping everything from immediately after the claim onward in
      one try/catch, with a conditional status update
      (`updateStatusIfCurrent`) so the catch-all failure handler no
      longer clobbers a more specific status (`SOURCE_CHANGED`,
      `CONFIG_CHANGED`) set moments earlier in the same call. Also
      added: a manual recovery endpoint for a batch genuinely stuck by
      a real process crash (deliberately not an automatic time-based
      reclaim — see `mapping-notes.md` for why), and an "at most one
      active proposal per batch" policy (application-level check plus a
      partial unique index as a database-level backstop) closing a
      separate bug where repeated `/propose` calls could create
      multiple proposals racing for one batch.
- [x] **Step 7.4** — A fifth external review found that Step 7.3's
      "at most one proposal per batch" fix didn't make a *replacement*
      proposal actually approvable: re-proposing after a rejection or
      failure never reset the batch back to `PENDING`, so the new
      proposal could be claimed as `APPROVED` (permanently, by design)
      but then fail its own batch claim, with no path forward at all.
      Fixed by generalizing the atomic-claim mechanism (previously
      hardcoded to claim into `PROCESSING`) to also claim a batch into
      a new `PROPOSING` status *before* the LLM call starts — the same
      pattern already used for delivery, applied to proposing too. A
      real side benefit: two concurrent `/propose` calls now cause at
      most one LLM invocation, not just at most one saved proposal.
      Also fixed: `GET /proposals/{id}` no longer leaks another
      proposal's delivery history (was scoped by batch, now by
      proposal), and the manual recovery endpoint is now explicitly
      documented as a break-glass operation, not a routine UI action.
      See `mapping-notes.md` for the full account, including a
      disagreement with one review suggestion (wrapping `/approve`'s
      two claims in a database transaction) that a sixth review proved
      **wrong** — see Step 7.5.
- [x] **Step 7.5** — A sixth external review demonstrated, with a
      concrete interleaving, that Step 7.4's reasoning for not needing
      a database transaction around `/approve`'s two claims was
      incorrect: claiming the proposal and claiming the batch were
      still two separate statements, and a concurrent `/propose` could
      claim the batch in the real window between them, reproducing the
      exact "approved but never processed" failure Step 7.4 was
      supposed to have fixed. Fixed properly this time with a new
      `ProposalDecisionService`, wrapping the proposal+batch claims for
      `/approve` and `/reject` in real `@Transactional` methods (no new
      dependency — `spring-boot-starter-jdbc` already provides Spring's
      transaction support), plus the same treatment for completing
      `/propose` (saving the proposal and releasing the batch back to
      `PENDING` atomically). Also extended the manual recovery endpoint
      (renamed to `/batches/{id}/recover-stuck`) to handle a batch
      stuck in `PROPOSING`, not just `PROCESSING`. See
      `mapping-notes.md` for the honest account of what the earlier
      reasoning got wrong, not just what changed. A follow-up curl-based
      concurrency test came back safe but ambiguous about *why* — proved
      properly instead with `ProposalDecisionServiceTransactionalTest`,
      the project's first Testcontainers-backed integration test against
      a real Postgres. **`mvn test` now needs Docker available** to pass
      (a genuine first for this project) — see `mapping-notes.md` for
      what that test actually proves and the honest risk assessment on
      code that, unlike the rest of this project, hasn't yet been run
      against a real build.
- [x] **Step 8a** — Backend groundwork the review UI needs to exist at
      all, done ahead of the React app itself: shared-secret auth on
      every `/internal/**` endpoint (`ApiKeyAuthFilter`, a single
      secret appropriate for one organization's integrated UI, not
      multi-tenant complexity), durable row-level validation reports
      (`validation_run` — flagged repeatedly across Step 7.1/7.2 as
      needed early, since a reviewer can't see what happened to a past
      proposal without them), the queue/detail read endpoints
      (`GET /proposals`, `GET /proposals/{id}`), and a reject endpoint
      (`POST /proposals/{id}/reject`, same atomic compare-and-set idiom
      as approve).
- [x] **Step 8b** — The review UI itself, built with React, integrated
      into agentic-sheets rather than designed for embedding into
      third-party applications — the project's main goal is processing
      spreadsheets into canonical data, and a genuinely reusable,
      embeddable review widget is better scoped as its own separate
      project later, built against this project's REST API from the
      outside. First pass done: project scaffold (Vite + React +
      TypeScript, real build verified — `npm run build` and `npm run
      lint` both actually run and pass, unlike most of this project's
      backend work which could only be verified by the person running
      `mvn test` themselves), the design system (palette drawn directly
      from `docs/images/agentic-sheets-drift-resilient-architecture.svg`
      for real visual consistency with the docs), API-key auth matching
      `ApiKeyAuthFilter`, and the review queue itself — which needed a
      small, well-motivated backend addition
      (`MappingProposalRepository.findQueueEntries`, joining
      `import_batch` so the queue shows what a proposal is actually
      for, not a bare ID). Second pass done: the actual review screen
      — proposed field mappings with per-field confidence (source
      column/constant, variant resolution, transformations all shown),
      working approve/reject (with a reason field for rejection), a
      retry-delivery action for a batch stuck at `DELIVERY_FAILED`/
      `PROCESSING_ERROR`, and validation/delivery history rendered
      usefully rather than as raw JSON. Third pass done: "edit" — the
      last unbuilt verb in "approve/edit/reject" — via a new
      `/proposals/{id}/amend` endpoint, `ProposalDecisionService
      .amendProposal`, and a new `SUPERSEDED` proposal status
      (deliberately distinct from `REJECTED`: "corrected" and "wrong"
      are different facts). The frontend side is a direct JSON editor,
      not a full per-field form — honestly scoped as a functional
      first version, not a placeholder. Fourth pass done: source
      spreadsheet columns and sample values, shown inline in
      `FieldMappingTable` next to each proposed field rather than a
      separate panel — closed once a real `/internal/explore/table`
      response was confirmed rather than guessed at (an untyped
      `JsonNode` on the backend, no fixed Java record to verify the
      shape against). Every piece of the original Step 8 scope is now
      built. See `frontend/README.md` and `ui-notes.md`'s Step 8b
      section for the full account.
- [x] **Step 8c** — A thorough external UX/operational-safety review of
      the actual built UI (not just the code) found several confirmed
      bugs: no reachable "Change API key" action despite the queue's
      own error message pointing at one, source-sample load failures
      silently swallowed into an indistinguishable "no samples exist"
      state, one shared error state covering two different failures on
      the detail page, confidence coloring that contradicted its own
      documented intent (red below 60%, despite the code's own comment
      saying low confidence shouldn't look like a failure), unlabeled
      decision-vs-delivery status pills, and no cancel path once a
      rejection reason input was open. Fixed all of these, plus a
      compact pre-approval risk summary, ARIA table semantics for the
      div/span-based grids, a horizontal-scroll responsive fallback,
      continuous JSON validation with Reset in the edit panel, and a
      "Needs attention" queue filter — which needed a real backend
      change (`GET /proposals` accepting multiple `status=` values, not
      just one) since "needs attention" is six different statuses, not
      one. See `ui-notes.md`'s Step 8b section for the full account,
      including what's deliberately deferred (a confirmation-gate
      modal, full responsive redesign, automated frontend tests, and
      more) with reasoning for each.
- [ ] **Step 8d** — End-to-end regression testing, paused for
      deliberately before Step 9: every verification of this project's
      actual working behavior so far has depended on a human running
      curl or clicking through the app by hand, which isn't regression
      protection against a future change quietly breaking something
      that already worked. **Checkpoint A: done and confirmed** — a
      black-box golden-path test — real backend container, real
      Postgres, real sheets-reader-mcp,
      [llmsim](https://github.com/pramalin/llmsim) standing in for the
      one genuinely nondeterministic dependency (a real model call),
      real validator and dispatcher — driving `/propose` through
      delivery and asserting business results (actual field mappings,
      validation row counts, delivery outcome, exactly one model call),
      not just terminal status. Four real runs against this project's
      own machine found and fixed three genuine bugs (a host-port
      collision `-p` project isolation alone didn't cover, an API key
      never wired to match between the test and the backend, a
      working-directory bug in the cleanup trap, and a stale hardcoded
      model string in the test itself — this project's own
      `application.yml` had already decided never to pin one, for
      exactly the reason the test then ran into) before the fourth run
      came back clean: `1 passed (3.6s)`, confirming **the entire
      pipeline — propose through delivery — actually works end to
      end**, on a real machine, not just internally verified. A second
      external review then found a real gap even in that confirmed-
      passing test: it never verified the payload that actually crossed
      the delivery boundary, only that dispatch reported success —
      `FakeTargetController` accepted any body and stored nothing.
      Fixed with a request journal mirroring llmsim's own
      `GET /_llmsim/calls` pattern exactly (a full list, not just "the
      most recent request" — gives both content *and* count as one
      assertion). That fix had a real bug of its own, found the same
      way every other bug in this initiative was found — an actual
      execution: HTTP headers are case-insensitive by protocol, but the
      capture logic did an exact-string lookup against Dispatcher's
      real mixed-case header names. Fixed properly (case-insensitive
      matching, not a casing guess), locked in with a new
      `FakeTargetControllerTest`, and reconfirmed passing:
      `mvn test` 77/77, `run-golden-path.sh` `1 passed (3.4s)`. Also
      pinned `sheets-reader-mcp` in CI to a verified real commit,
      made concurrent E2E runs safe (unique project name, overridable
      ports), and bumped three GitHub Actions to their real current
      major versions (all verified directly via `git ls-remote`, not
      assumed). Also
      added: CI now
      builds/lints the frontend at all (previously only `mvn test`
      ran). See `e2e/README.md` for the full round-by-round history —
      preserved in full, not overwritten, matching how
      `mapping-notes.md` keeps every hardening round's history rather
      than just the current end state — and Checkpoint B's plan (two
      Playwright browser journeys: an
      approval flow and the Step 8c wrong-key-recovery path).
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
