# Inbox scanner notes

The reasoning behind Step 9's design, kept separate from
`mapping-notes.md` (mapping/ADT logic) and `ui-notes.md` (the review
UI) the same way those two are kept separate from each other -- this
one is about automatic discovery and routing: how a file lands in an
inbox and becomes a proposal without a human ever calling `/propose`
by hand.

## Scope

A scheduled scanner that watches an inbox directory, parses each
filename into a canonical model and client, dedupes on content, hands
new files to the existing mapping pipeline, and -- once a batch is
fully delivered -- archives the source file out of the inbox. The
review/approve/deliver pipeline itself (Steps 5-8) is unchanged; Step 9
is purely about *how a batch gets created* in the first place, as an
alternative to a human or script calling `/propose` directly.

## Design conversation: three external review rounds

The design went through three rounds of external review before any of
this was built, each catching something real. Worth preserving the
actual reasoning, not just the final shape, since a couple of the
corrections reverse an instinct that seemed reasonable at the time.

### Round 1: feed routing belongs with the client, not the model

My first instinct was to add `feedType`/`worksheetNames` fields
directly to `canonical-models/*.yaml`, reasoning that a canonical
model is the natural owner of "what feed produces me." A review round
correctly reversed this: `ClientConfig` already exists specifically
for source-side conventions (its own javadoc says so), while
`CanonicalModel` governs the *output* shape and has no reason to know
which clients feed it or what they call their worksheets. Confirmed
by checking `ClientConfig.java` directly before accepting the
correction, not just deferring to it.

The real-world case that makes this concrete: `rate_reset_pimco_20250501.xlsx`
has `feedType = rate_reset`, targets model `MarketRateBookValue`, and
the actual worksheet is named `RateReset` -- three different strings,
no rule connects them. That's exactly why this is explicit
per-client config (`client-configs/pimco.yaml`'s `feeds:` map), not
inferred.

### Round 2: the scanner must never repeat a manual /propose's eligibility

The existing `/propose` endpoint deliberately allows re-proposing from
`REJECTED`, `VALIDATION_FAILED`, `SOURCE_CHANGED`, `CONFIG_CHANGED`,
and `PROPOSING_ERROR` -- built that way on purpose in Step 7.4, for a
human explicitly asking for a fresh attempt. A review round caught
that if the scanner called the same method on every scan cycle, it
would silently re-propose every rejected file forever, undoing a
human's decision without anyone asking for it. Fixed by splitting
`MappingWorkflowService` into two entry points with genuinely
different eligibility:

- `proposeManually(...)` -- unchanged behavior, all six statuses,
  human-initiated recovery.
- `proposeInitialFromInbox(...)` -- only ever creates the *first*
  proposal for a batch (`PENDING` only). Re-proposing after rejection
  stays a deliberate, human-initiated action through the existing
  UI/API, never something the scanner decides on its own.

### Round 3: physical file identity is not the same thing as a unit of work

My own first attempt at scanner-side dedup was a plain existence check
against `import_batch` ("has any batch ever existed for this
filename+hash"). A review round found two real problems with this,
both confirmed by walking through concrete scenarios rather than
taking the critique on faith:

1. **A check-then-act race.** Two backend instances that loaded
   different config versions could both observe "no batch exists" and
   both call the model for the same physical file -- `import_batch`'s
   own unique key includes `config_version`, so a plain existence
   check isn't atomic against that.
2. **Too broad in the other direction.** A batch that hit
   `PROPOSING_ERROR` without ever producing a proposal would be
   permanently skipped forever by an "any batch exists" check --
   directly undermining the recovery semantics Step 7.4 built on
   purpose for that status.

The fix: a separate `inbox_file` table, its own identity
(`UNIQUE (logical_filename, content_hash)`, deliberately narrower than
`import_batch`'s), and its own atomic claim
(`INSERT ... ON CONFLICT DO NOTHING` for first discovery, a leased
`UPDATE` for retries). See `db/init/01-orchestration-schema.sql`'s own
comment on that table for the full reasoning.

One correction *to* a review round, not just from one: an earlier
draft of this design claimed `import_batch`'s unique constraint
"serves the approval-time `CONFIG_CHANGED` detection." Checked against
the real code before accepting that framing -- it doesn't.
`CONFIG_CHANGED` is a runtime comparison (`currentModel.version() !=
stored.configVersion()`) at approval time; the constraint just keeps
distinct config-version work units from colliding. Worth naming
because it's the kind of imprecision that's easy to let slide once a
review has moved on to bigger points, and this project's whole
practice has been not letting that happen.

### A note on stale reviews

Two separate review rounds referenced code as broken that had already
been fixed in an earlier session (`findOrCreate`'s select-then-insert
race, `FileHasher`'s memory/symlink handling) -- both times because the
review was working from GitHub's `main`, which was behind local,
uncommitted work. Not a criticism of either review; both were upfront
about their source. Worth recording as a real, recurring risk of this
project's local-iteration-heavy workflow: push before asking for
external review, or expect this exact class of false alarm.

## Architecture, as built

```
client-configs/<client>.yaml
    feeds:
      <feedType>: { modelId, worksheetNames: [...] }
        |
        v
CanonicalModelRegistry.resolveRoute(clientId, feedType) -> FeedRoute
    (one atomically-published RegistrySnapshot -- see below)
        |
        v
InboxScanner (scheduled, off by default)
    1. list stable candidates (skip .part/~$*/hidden/symlink/non-regular)
    2. hash + recordArrival + claimForProcessing  <- atomic, inbox_file's own identity
    3. parse filename (strict, from the right -- see InboxFilenameParser)
    4. resolve route (client, feedType) -> model + worksheet candidates
    5. resolve actual worksheet (WorksheetResolver -- exact match against
       visible worksheets, fails deterministically on 0 or 2+ matches)
    6. MappingWorkflowService.proposeInitialFromInbox(...)
        |
        v
    (existing Step 5-8 pipeline: review, approve, validate, dispatch)
        |
        v
InboxArchiver (scheduled, separate pass, delivered-only)
    finds inbox_file rows whose linked batch reached DELIVERED,
    atomically claims, moves the file to
    archive/delivered/{client}/{feedType}/{date}/{batchId}-{hashPrefix}-{filename}
```

### `RegistrySnapshot`: one atomic swap, not two independent fields

`CanonicalModelRegistry` used to hold `models` and `clients` as two
separate `volatile` fields, swapped one after the other in `reload()`.
Harmless while they were unrelated, but a real torn-read window once a
client's feed route needs to reference a model from the same reload
cycle -- a reader between the two swaps could see a new model map
alongside a stale client map. Fixed by replacing both with one
`RegistrySnapshot` record (`models`, `clients`, and the
`(clientId, feedType) -> FeedRoute` index, all built together and
published as a single reference swap). A feed referencing an unknown
model fails that one client's config in isolation, at reload time --
the same per-file failure isolation `CanonicalModelRegistry` already
had for models, extended to route validation.

### Filename parsing: from the right, not a fixed split count

`<feedType>_<client>_<yyyyMMdd>.<ext>` -- strip the extension, take
the trailing token as a strict date, the token before that as the
client, everything remaining as the feed type. A naive `split("_")`
expecting exactly three segments breaks on `rate_reset_pimco_20250501.xlsx`,
whose feed type is itself two words. `InboxFilenameParser` returns
`Optional.empty()` rather than throwing on anything that doesn't fit --
an unparseable filename is routine input for a directory this system
doesn't control the contents of, not an application error.

### Quarantine: a real, queryable record for every permanent failure

Originally, four failure modes (unsupported extension, unparseable
filename, unknown route, ambiguous worksheet) only logged and moved
on -- no `inbox_file` row, since hashing (and therefore establishing
identity) happened *after* all four checks. Restructured so hashing
and the atomic claim happen first, before any routing checks: every
permanent failure now produces a real `QUARANTINED` row via
`InboxFileRepository.markQuarantined`, visible and queryable, not just
a log line repeating every scan cycle forever. Side benefit: the claim
now covers the whole per-file sequence, not just the final propose
call, closing a smaller race where two concurrent scanner instances
could redundantly parse/route the same file before either won.

### Two real bugs found only by actually running it

Every piece above was unit-tested before ever running against a real
stack. Two bugs survived that anyway, both found on the very first
live executions:

1. **Ambiguous column reference in a JOIN.** `InboxFileRepository`'s
   `SELECT_COLUMNS` constant was written for a single-table query,
   then reused unqualified inside `findDeliveredAwaitingArchive`'s
   join against `import_batch` -- which happens to share five column
   names with `inbox_file` (`id`, `client_id`, `content_hash`,
   `worksheet`, `status`). Postgres correctly rejected the ambiguous
   reference. Fixed by qualifying every column with the `f.` alias
   and aligning both queries to the same alias consistently.
2. **`ResolverStyle.STRICT` + lowercase `y` (year-of-era) genuinely
   can't resolve to a `LocalDate`** without an explicit era also
   present, even though the individual fields parse correctly.
   Confirmed by actually running it in a real JVM before guessing at a
   fix: `uuuu` (plain year) resolves correctly and still rejects
   invalid calendar dates.

Neither of these is the kind of thing code review reliably catches --
both are "looks obviously fine reading the code, fails the moment
something real touches it" bugs, which is the entire reason this
project holds "confirmed by running it" as a higher bar than "compiles
and passes unit tests in isolation."

## What was proven, and how

**By hand, first**: a real file dropped into `sample-input/inbox/`,
discovered by the real scheduled scanner, routed via real
`client-configs/jpmc.yaml`, worksheet-resolved via a real
`list_worksheets` call, proposed via a real LLM call, reviewed and
approved through the actual UI, validated, dispatched, delivered, and
archived to `archive/delivered/jpmc/holdings/2026-07-31/1-9baa36056ad4-holdings_jpmc_20260731.xlsx`
-- confirmed via `ls` before and after.

**Automated, second**: `e2e/tests/inbox-scanner.spec.ts`, a third
Playwright project (`inbox`) alongside `api` and `browser`, its own
Compose overlay (`compose.e2e-inbox.yaml`) and runner
(`run-inbox-tests.sh`). Deliberately isolated from `sample-input/`
entirely -- a fresh `mktemp -d` workspace per run, mounted at a
non-colliding container path (`/workspace-e2e-inbox`), rather than
betting on Compose's exact volume-merge semantics to override the
shared `/workspace` mount across three stacked `-f` files. The scanner
stays off in the other two E2E suites' shared overlay
(`compose.e2e.yaml`) on purpose -- a background scan racing an E2E
test for llmsim's single scripted reply is exactly the "unexpected
extra model call" failure class Step 7.4/7.5 hardened against; see
`e2e/README.md`.

One bug on the first automated run: `__dirname` doesn't exist in this
project's ESM setup (`e2e/package.json` sets `"type": "module"`) --
the first test file in the whole E2E suite to need path resolution
relative to itself. Fixed with the standard `import.meta.url`
equivalent. Second run: `1 passed (7.4s)` -- the entire pipeline,
discovery through archive, fully automated, zero manual steps.

Both confirmed working locally before being added to CI (`e2e-inbox`
job, gated on the golden path passing first, same bar Checkpoints A
and B were held to) -- the CI run itself hasn't been watched yet as of
this writing; local success and a fresh GitHub Actions runner are
still two different claims.

## What's intentionally not covered

- **Concurrent scanner instances.** The atomic claim (`inbox_file`'s
  `ON CONFLICT`/leased `UPDATE`) is designed to be safe under this,
  but nothing has actually run two scanner instances against the same
  inbox at once.
- **The archiver's own crash-recovery path.** A move that fails
  partway through leaves the row at `ARCHIVING` with no automatic
  reconciliation (`InboxArchiver`'s own comment names this gap
  explicitly) -- "source missing but destination exists" isn't
  handled.
- **Retry/lease behavior after a genuine transient failure** (a real
  database or model outage mid-scan) has never been exercised live,
  only unit-tested against mocked failures.
- **Migration tooling.** `inbox_file` was added the same way every
  other schema change in this project has been -- edit
  `db/init/01-orchestration-schema.sql`, `docker compose down -v` to
  pick it up. Fine for a single-environment project; would need real
  migration tooling (Flyway or similar) before this schema needs to
  evolve across environments without a full rebuild.
