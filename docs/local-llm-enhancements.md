# Local LLM Enhancements

Status: in progress
Initial entry: 2026-08-08

## Motivation

`docs/local-llm-evaluation.md` and `docs/dgx-spark-evaluation.md` record how
Agentic Sheets performs against locally hosted Qwen 2.5 models (3B, 7B, 14B,
32B) on a DGX Spark. That evaluation reached a conclusion worth acting on
architecturally, not just noting: even the 32B model reliably produced a
correct, repeatable proposal except for one class of field -- sum-type
variant resolution (`currency`, `asset_class`) -- and the reason wasn't model
capacity. The correct value was already deterministically derivable from the
source data the application reads anyway:

```text
sourceColumn = Currency
observed values = {USD}
canonical legal variants include USD
=> selectedVariant = USD
```

Asking a progressively larger model to re-derive a fact the application
already has in hand is the wrong lever to pull. This phase moves that class
of decision out of the LLM and into deterministic code, narrowing the LLM's
job to genuine ambiguity -- inferring a column mapping for a source layout
the system hasn't seen before -- rather than treating it as the mapping
engine for facts already available.

This phase is additive to, not a replacement for, Step 10's mapping memory:
mapping memory reuses a whole previously-approved proposal when a file's
column layout repeats; this phase instead makes each *fresh* proposal more
correct by resolving sum-type fields deterministically before a human ever
sees them, independent of whether memory has seen this layout before.

## Roadmap

- [x] **Step LLM-1** -- Reusable spreadsheet row reader. Extract the
  paginated `read_rows` loop out of `ProposalValidationService` into
  `SpreadsheetRowReader`, with no behavior change, so the same full-source-row
  read can be reused by the resolver below instead of only ever seeing
  `describe_table`'s sample values. Confirmed live: 133/133 (up from 128 by
  exactly the five new tests), no other test count moved.
- [ ] **Step LLM-2** -- Deterministic sum-type resolver
  (`SumTypeMappingResolver`). Reads full observed source rows via
  `SpreadsheetRowReader`; fills a `selectedVariant`/`variantValueMap` the
  agent left unresolved when it's uniquely derivable from observed data;
  validates model-supplied variant metadata against every observed row, not
  just a sample; never guesses on ambiguous or contradictory input. No
  dependency on client configuration (Step LLM-3) -- canonical-name matching
  only.
- [ ] **Step LLM-3** -- Client conventions. Versioned field aliases and
  value vocabulary per client, initially still backed by
  `client-configs/*.yaml`; validated at load time against the current
  canonical model; a durable place for knowledge like "JPMC's 'Fixed Income'
  column value means canonical variant `FixedIncome`" that shouldn't have to
  be rediscovered by the LLM on every file.
- [ ] **Step LLM-4** -- Resolution integration. Wires client conventions
  ahead of canonical-name matching (explicit convention wins; canonical
  matching is the fallback for values a client hasn't configured), extends
  mapping-memory provenance to include client-config version, and considers
  whether known field aliases can let deterministic code resolve some column
  mappings before the LLM is invoked at all.
- [ ] **Step LLM-5** -- Business-user authoring workflow for client
  conventions (approve a proposal -> optionally "remember" a convention;
  flat/tabular editor, not hand-edited YAML). Deferred until LLM-1 through
  LLM-4 are proven against the real `jpmc.yaml` fixture.
- [ ] **Step LLM-6** -- Re-scoped benchmark. Once known conventions are
  resolved deterministically, rerun the 3B/7B/14B comparison against a
  narrower task -- a known file plus one deliberately unfamiliar column --
  to see whether a materially smaller model is sufficient once it's only
  being asked to resolve genuine ambiguity.

## Step LLM-1 build notes

**What moved.** `ProposalValidationService`'s private `fetchAllRows`/
`toStringMap` methods (the `read_rows` pagination loop, `PAGE_SIZE = 500`,
`hasMore`-driven termination) are now `SpreadsheetRowReader.readAll(path,
worksheet)`, a `@Service` in the same `mapping` package.
`ProposalValidationService` now takes `SpreadsheetRowReader` in its
constructor instead of `SpreadsheetExplorerService`/`JsonMapper` directly,
and calls `rowReader.readAll(...)` where it previously called its own
private `fetchAllRows(...)`.

**Why the `mapping` package, not `spreadsheet`.** The Step LLM-2 resolver
this extraction exists to support lives in `mapping` alongside
`ProposalValidationService` and `CanonicalRowBuilder`, and needs the same
full-row read. Keeping the reader in `mapping` (wrapping
`SpreadsheetExplorerService` from `spreadsheet`, rather than living in
`spreadsheet` itself) matches where its two current/planned consumers
actually are, and keeps `spreadsheet` scoped to raw MCP tool access rather
than mapping-specific row aggregation.

**No behavior change.** Same page size (500), same `hasMore` termination
condition, same per-row shape (`LinkedHashMap<String, String>`, preserving
insertion order and `null` cell values as `null` rather than the string
`"null"`), same error propagation (an MCP tool error still surfaces as
whatever `SpreadsheetExplorerService.readRows` itself throws -- currently an
`IllegalStateException` -- completely unchanged by this extraction, since
`SpreadsheetRowReader` doesn't catch anything).

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/SpreadsheetRowReader.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ProposalValidationService.java` (pagination logic removed, delegates to the new reader)
- `backend/src/test/java/com/alai/agenticsheets/mapping/SpreadsheetRowReaderTest.java` (new)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ProposalValidationServiceTest.java` (updated to construct `ProposalValidationService` via the new two-argument constructor)

**Tests added** (`SpreadsheetRowReaderTest`):
- fewer than 500 rows -> one call
- exactly 500 rows with `hasMore=false` -> still one call (the boundary is
  `hasMore`, not row count)
- more than 500 rows -> a second page is actually requested at `offset=500`,
  and rows from both pages come back in the original order
- zero rows (empty worksheet) -> terminates after one call rather than
  looping or making an unnecessary second request
- MCP read failure -> propagates unchanged, not swallowed or wrapped

**Confirmed live.** This extraction was written against the real cloned
source (`ProposalValidationService`, `SpreadsheetExplorerService`,
`ImportBatch`, and the existing `ProposalValidationServiceTest` were all read
directly, not guessed at, consistent with this project's established
"verify, don't guess" discipline -- see `mapping-notes.md`), then actually
run via `mvn test` locally. First run silently exercised the pre-existing
code -- an overlay step didn't land before the build ran, caught by
noticing the unchecked-operations compiler warning still pointed at
`ProposalValidationService.java` instead of the newly extracted
`SpreadsheetRowReader.java`, and by `SpreadsheetRowReaderTest` never
appearing in the `Running ...` list even though the total (128) matched
the pre-Step-LLM-1 count exactly. Re-run after confirming via `git status`
that the new/changed files were actually present: **133/133**, up from the
prior 128 by exactly the five new `SpreadsheetRowReaderTest` cases, nothing
else moved. `SpreadsheetRowReader.java` now correctly carries the
unchecked-operations warning that previously sat on
`ProposalValidationService.java`, confirming the extraction actually moved
the raw-type `Map.class` conversion, not just added a delegating call.

**Nothing discovered here changes the plan for Step LLM-2.** `readAll`'s
signature (`(path, worksheet) -> List<Map<String, String>>`) is exactly the
shape the resolver needs: full observed rows, keyed by source column header,
values as strings ready for the normalized-matching rules already designed.
