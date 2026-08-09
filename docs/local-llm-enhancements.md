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
- [x] **Step LLM-2** -- Deterministic sum-type resolver
  (`SumTypeMappingResolver`). Reads full observed source rows via
  `SpreadsheetRowReader`; fills a `selectedVariant`/`variantValueMap` the
  agent left unresolved when it's uniquely derivable from observed data;
  validates model-supplied variant metadata against every observed row, not
  just a sample; never guesses on ambiguous or contradictory input. No
  dependency on client configuration (Step LLM-3) -- canonical-name matching
  only. Confirmed live: 148/148 (up from 133 by exactly the 15 new
  `SumTypeMappingResolverTest` cases), no other test count moved.
- [x] **Step LLM-3** -- Client conventions. Versioned field aliases and
  value vocabulary per client, initially still backed by
  `client-configs/*.yaml`; validated at load time against the current
  canonical model; a durable place for knowledge like "JPMC's 'Fixed Income'
  column value means canonical variant `FixedIncome`" that shouldn't have to
  be rediscovered by the LLM on every file. Confirmed live: 167/167 (up
  from 148 by exactly the 19 new tests), no other test count moved.
- [x] **Step LLM-4** -- Resolution integration. Wires client conventions
  ahead of canonical-name matching (explicit convention wins; canonical
  matching is the fallback for values a client hasn't configured), extends
  mapping-memory provenance to include client-config version, and considers
  whether known field aliases can let deterministic code resolve some column
  mappings before the LLM is invoked at all. Confirmed live: 173/173 (up
  from 167 by exactly the 6 new tests), no other test count moved. The
  field-alias piece was deferred at the time this step was originally
  closed out -- see "Field-alias resolution: the deferred architectural
  step, finally built" below for where it actually landed, following an
  external review. See build notes below -- mapping-memory provenance turned out to already be
  covered by Step LLM-3's `ClientConfigFingerprint` extension, and
  field-alias-driven column resolution is deliberately deferred, not
  built this round.
- [x] **Step LLM-5** -- Business-user authoring workflow for client
  conventions (approve a proposal -> optionally "remember" a convention;
  flat/tabular editor, not hand-edited YAML). Deferred until LLM-1 through
  LLM-4 were proven against the real `jpmc.yaml` fixture, which they were.
  Scoped to backend groundwork this round (a validated suggestion-capture
  API, mirroring how Step 8a shipped backend groundwork ahead of Step 8b's
  actual UI) -- writing the suggestion into `client-configs/*.yaml` itself
  ("apply") and any frontend affordance remain explicitly deferred; see
  build notes below for the full reasoning.
- [x] **Step LLM-6** -- Re-scoped benchmark. Once known conventions are
  resolved deterministically, rerun the 3B/7B/14B comparison against a
  narrower task -- a known file plus one deliberately unfamiliar column --
  to see whether a materially smaller model is sufficient once it's only
  being asked to resolve genuine ambiguity. Real findings against
  `qwen2.5:3B-Q4_K_M`, confirmed from raw model output, not inferred:
  the model does **not** resolve currency/asset_class itself -- the
  deterministic resolver is doing exactly the work this phase built it
  for -- and the unfamiliar-column run surfaced a distinct, specific
  failure mode (the model echoing its own JSON-Schema formatting
  instructions back as if they were response data, not a graceful
  decline or a wrong guess), which this phase's own layers (the null
  fix, the empty-mappings check) correctly caught as a clean 422. 7B/14B
  and DGX Spark comparisons remain open, natural continuations of the
  same benchmark, not blocking anything -- see build notes below.

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

## Step LLM-2 build notes

**What was added.** `MappingResolutionProblem` (a typed record with a
four-value `Kind` enum -- `UNRESOLVED`, `SEMANTIC_CONFLICT`,
`CLIENT_CONFIGURATION`, `CONFIGURED_OVERRIDE_NOTABLE` -- and a `blocking`
flag) and `SumTypeMappingResolver`, both new files in the `mapping`
package. Only `UNRESOLVED` and `SEMANTIC_CONFLICT` are actually
constructed by this resolver; the other two `Kind`s exist now so the type
doesn't need to change shape again once client conventions (Step LLM-3)
and their integration (Step LLM-4) land.

**Resolution rules, exactly as designed across this phase's planning:**
- A sum type field mapping with neither `selectedVariant` nor
  `variantValueMap` set: collect every distinct non-blank value from its
  `sourceColumn` across the full observed rows; if every value uniquely
  resolves to one canonical variant, fill `selectedVariant`; if they
  resolve to more than one, fill a complete `variantValueMap`; if any
  value doesn't uniquely resolve, leave the field exactly as proposed and
  report `UNRESOLVED` -- never guess.
- An existing `selectedVariant` (with a `sourceColumn` present): every
  distinct observed value must resolve to that same variant, or it's
  reported as `SEMANTIC_CONFLICT` and left unrepaired. This is the check
  that would have caught the 3B benchmark's `selectedVariant=Equity`
  proposal against a column that actually contained both `Equity` and
  `Fixed Income` rows.
- An existing `variantValueMap`: every distinct observed value must be a
  key in the map, or it's `SEMANTIC_CONFLICT`, unrepaired.
- `selectedVariant` and `variantValueMap` both set (structurally
  contradictory) is never touched -- left for
  `MappingProposalStructuralValidator` to reject, unchanged, exactly as
  designed.
- Variant name matching is three-tier and strictly deterministic: exact
  match after trimming whitespace, then case-insensitive, then normalized
  (lowercased, `\s`/`_`/`-` stripped). A tier matching more than one
  variant is treated as ambiguous, not resolved, and falls through (or, at
  the last tier, is left unresolved). No edit distance, no embeddings, no
  LLM call.
- Non-sum-type field mappings are never inspected or altered.

**Integration point, exactly where designed.** `AgentMappingProposalService.propose()`
now calls `SumTypeMappingResolver.resolve(...)` immediately after decoding
the model's structured output and before `MappingProposalStructuralValidator.validate(...)`
-- the resolver's blocking problems and the structural validator's
problems are combined into one list, and either non-empty list still
throws the existing `MappingProposalValidationException` (no API change).
The *resolved* proposal (not the raw model output) is what gets validated
and, on success, returned and persisted. `validateEdited()` (the `/amend`
path) deliberately does not call the resolver -- a human-edited proposal
is validated exactly as submitted, never silently enriched, matching how
Step 10's mapping memory also treats `/amend` output as authoritative
rather than something to re-derive.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/MappingResolutionProblem.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/SumTypeMappingResolver.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- resolver wired into `propose()`, constructor takes the new dependency)
- `backend/src/test/java/com/alai/agenticsheets/mapping/SumTypeMappingResolverTest.java` (new)

**Tests added** (`SumTypeMappingResolverTest`, against the real `canonical-models/holdings.yaml`
test fixture -- `Currency` = `{USD,EUR,GBP,JPY,CAD}`, `AssetClass` =
`{Equity,FixedIncome,Cash,Alternative}`):
- `Currency={USD}` with no variant set -> fills `selectedVariant=USD`
- `Asset Class={Equity, Fixed Income}` with no variant set -> fills a
  complete `variantValueMap`, "Fixed Income" resolving to `FixedIncome`
  via normalized matching
- an existing correct `selectedVariant` -> preserved unchanged
- an existing correct, complete `variantValueMap` -> preserved unchanged
- `selectedVariant=Equity` against a column that also contains "Fixed
  Income" -> `SEMANTIC_CONFLICT`, not repaired (the 3B benchmark's exact
  failure mode)
- an incomplete `variantValueMap` missing an observed value -> `SEMANTIC_CONFLICT`,
  not repaired
- an unknown source value (e.g. "Bitcoin") -> `UNRESOLVED`, no mapping
  invented
- an all-blank source column -> `UNRESOLVED` ("nothing to derive from"),
  not treated the same as a genuine ambiguity
- two variant names that collide after normalization (a synthetic model
  built for this test, since no real fixture happens to have colliding
  names) -> `UNRESOLVED`, not arbitrarily chosen
- trailing/leading whitespace in an observed value (`"USD "`, `" USD"`)
  -> still resolves via the trim-then-exact tier, not treated as a
  different token
- `selectedVariant` and `variantValueMap` both set -> untouched, zero
  problems reported (left for the structural validator)
- a non-sum-type field mapping alongside a sum-type one -> the primitive
  field is returned byte-for-byte identical
- a proposal with zero sum-type field mappings -> `SpreadsheetRowReader.readAll`
  is never called at all (`verify(reader, never())...`)
- a proposal with two sum-type fields -> `readAll` is called exactly
  once, not once per field
- 510 rows returned by a mocked reader -> every distinct value across the
  full set is aggregated correctly (pagination itself is `SpreadsheetRowReader`'s
  own tested responsibility; this confirms the resolver doesn't
  re-implement or truncate it)

**Written against the real source, not guessed at.** `MappingProposal.FieldMapping`'s
exact 8-field constructor order, `CanonicalModel`'s 6-field record shape,
`SumType`/`RecordType`/`TargetConfig`'s shapes, `CanonicalPaths.isSumTypePath`/`variantsAt`,
and `CanonicalRowBuilder.resolveVariant`'s actual behavior (confirming
`selectedVariant` really is trusted with zero row-level verification,
exactly as `mapping-notes.md`'s Step 10 section already documented) were
all read directly from the cloned repository before writing any of this,
not assumed.

**Confirmed live.** Unlike Step LLM-1, this landed clean on the first
attempt -- `mvn test` run against the real repo after overlaying these
files: **148/148**, up from Step LLM-1's 133 by exactly the 15 new
`SumTypeMappingResolverTest` cases, no other test count moved. Spring's
application context loaded successfully (`AgenticSheetsApplicationTests`
passed), confirming `AgentMappingProposalService`'s new constructor
parameter wired correctly with no bean-graph issues -- real evidence the
manual trace against the actual source (record shapes, `CanonicalPaths`,
`CanonicalRowBuilder`'s behavior) held up, not just a plausible-looking
guess.

## Step LLM-3 build notes

**Schema, as added.** `ClientConfig` gains a fourth field,
`conventions: Map<String, ClientModelConventions>`, keyed by canonical
model id (a client can feed more than one model, and a field path like
`currency` only means one specific thing within one specific model's
ADT -- scoping per model, not flat across the client, avoids that
ambiguity). `ClientModelConventions` carries two maps: `fieldAliases`
(canonical field path -> known alternate source column header names) and
`variantValues` (canonical sum-type field path -> {observed source value
-> canonical variant name}). In YAML:

```yaml
conventions:
  Holdings:
    fieldAliases:
      currency: [Currency, Ccy]
      asset_class: [Asset Class]
    variantValues:
      currency:
        USD: USD
      asset_class:
        Equity: Equity
        Fixed Income: FixedIncome
```

**A real discovery that changed the plan: no new `version` field was
needed.** The original design discussion (see this phase's planning
history) assumed `ClientConfig` would need an explicit incrementing
`version`, mirroring `CanonicalModel.version()`. Reading the actual
source first found that Step 10 had already solved this exact problem a
different way: `ClientConfigFingerprint` (used to scope mapping-memory
lookups) hashes `ClientConfig`'s mapping-relevant content instead of
relying on a version counter -- "a client's date convention... changing
invalidates a remembered mapping's continued safety the same way a model
version bump does," per its own javadoc. Reusing and extending that
existing mechanism (folding `conventions` into the hash, alongside the
already-hashed `dateFormat`) is simpler than introducing a second,
parallel versioning concept, and closes exactly the staleness risk the
original `version` field was meant to prevent: a mapping-memory entry
approved under one convention now correctly misses (not hits) once that
convention changes, because the fingerprint changes with it.

**A real architectural constraint discovered while implementing
validation.** Validating `fieldAliases`/`variantValues` against a
canonical model's actual field paths and variant names needs an
ADT-walking utility -- `CanonicalPaths` already does exactly this and is
well-tested, but it lives in the `mapping` package, and nothing in
`canonical` depends on `mapping` anywhere else in this codebase (the
dependency only ever runs the other way, confirmed by checking: zero
`canonical` files import from `mapping`, sixteen `mapping` files import
from `canonical`). Since this validation has to run from
`CanonicalModelRegistry` (itself in `canonical`, and already the single
place `feeds`' `modelId` references are validated), importing
`mapping.CanonicalPaths` from there would invert that established
boundary for one call site. The new `ClientConventionsValidator` (package-private, `canonical` package) instead reimplements a small,
deliberately narrow ADT walk -- just enough to know a model's valid
field paths and, for sum-type paths, their valid variant names -- rather
than moving or duplicating the full `CanonicalPaths` API. Documented
plainly as a deliberate, bounded duplication in the new class's own
javadoc, not something to "clean up" without noticing why it's there.

**Validation, run at config-load time from
`CanonicalModelRegistry.reloadClients`, in the same place and same style
`feeds`' `modelId` references are already checked:**
- a `conventions` entry's model id must exist in the current model
  snapshot;
- every `fieldAliases` key must be a real field path in that model;
- every `variantValues` key must be a real sum-type field path in that
  model, and every value it maps to must be a real variant of that
  field;
- no two distinct alias strings, across different canonical fields
  within one model's conventions, may normalize to the same thing
  (lowercased, whitespace/`_`/`-` stripped) -- an ambiguous alias would
  leave Step LLM-4's future column-alias lookup unable to tell which
  field a source header was actually meant for. The same alias string
  repeated under the *same* field is fine; only a cross-field collision
  is an error.

A client config with an invalid `conventions` entry fails exactly like
any other bad client config already does: logged, that one file's
previous good config (if any) stays in place, every other client config
still reloads normally -- no new failure mode, just the existing
per-file isolation extended to cover this new content.

**`ClientConfigFingerprint` iterates every map in sorted key order**,
deliberately not relying on `ClientConfig`'s own map iteration order --
`ClientConfigParser` builds its result maps via `Map.copyOf`, whose
iteration order the JDK explicitly does not guarantee matches insertion
order. Two semantically identical configs (same content, different
incidental map ordering) must always hash the same; this was verified
directly with a dedicated test, not just assumed from using a sorted
stream.

**The real `client-configs/jpmc.yaml` (and its byte-identical test
resource copy, kept in sync per this project's own established
schema-drift-avoidance discipline) now has a real `conventions:` block**,
using JPMC's actual confirmed values from `mapping-notes.md`'s mapping
table (`Fixed Income -> FixedIncome`, `Equity -> Equity`, `USD -> USD`)
plus one illustrative extra alias (`Ccy`) for a column name JPMC's real
fixture doesn't use but a different JPMC file plausibly could. This adds
real, validated content with zero behavioral effect on anything else --
nothing reads `ClientConfig.conventions()` at resolution time yet
(that's Step LLM-4), so the mapping agent's prompt, the golden-path E2E
test, and Step 10's mapping-memory test all see unchanged behavior. The
one real effect: `ClientConfigFingerprint.hash()` for `jpmc` changes
value (now includes real convention content), which only affects
mapping-memory *lookup scoping*, not any test assertion about a specific
hash value.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/canonical/ClientModelConventions.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/canonical/ClientConventionsValidator.java` (new, package-private)
- `backend/src/main/java/com/alai/agenticsheets/canonical/ClientConfig.java` (modified -- new `conventions` field)
- `backend/src/main/java/com/alai/agenticsheets/canonical/ClientConfigParser.java` (modified -- parses `conventions:`)
- `backend/src/main/java/com/alai/agenticsheets/canonical/CanonicalModelRegistry.java` (modified -- calls the new validator in `reloadClients`)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ClientConfigFingerprint.java` (modified -- conventions now included in the hash, deterministically sorted)
- `backend/src/test/java/com/alai/agenticsheets/canonical/ClientConfigParserTest.java` (modified -- new parsing tests appended)
- `backend/src/test/java/com/alai/agenticsheets/canonical/CanonicalModelRegistryTest.java` (modified -- new validation tests appended)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ClientConfigFingerprintTest.java` (new -- no test existed for this class before)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ProposalValidationServiceTest.java`, `MappingResolutionServiceTest.java`, `CanonicalRowBuilderTest.java` (modified -- updated to `ClientConfig`'s new 4-argument constructor; no behavioral change)
- `client-configs/jpmc.yaml` and `backend/src/test/resources/client-configs/jpmc.yaml` (modified -- real example `conventions:` block, kept identical between the two copies)

**Tests added:**
- `ClientConfigParserTest`: parses the real `jpmc.yaml` conventions
  correctly; an absent `conventions:` block parses to an empty map
  (PIMCO's fixture, unchanged); structural rejections -- `conventions`
  not a map, a `fieldAliases` entry not a list, a blank alias string, an
  empty `variantValues` sub-map.
- `CanonicalModelRegistryTest`: valid conventions load and are
  retrievable; conventions for an unknown model fail that client alone,
  not the whole registry (mirroring the existing `feeds` test exactly);
  a `fieldAliases` path that isn't a real field fails; `variantValues`
  against a non-sum-type field fails; `variantValues` mapping to an
  invalid variant name fails; two aliases under different fields that
  collide after normalization fail; the same alias repeated under the
  *same* field is explicitly confirmed **not** an error.
- `ClientConfigFingerprintTest` (new file): stable/deterministic for the
  same input; changing `dateFormat` changes the hash (pre-existing
  behavior, now covered); adding a field alias changes the hash;
  changing a `variantValues` target changes the hash (the exact
  "corrected a typo'd convention" scenario the design conversation
  flagged as a real risk); semantically-identical configs with different
  incidental map ordering hash identically; `feeds` is confirmed
  excluded from the hash (routing metadata, no bearing on mapping
  interpretation).

**Not yet done, deliberately -- this is Step LLM-4's job:** nothing
reads `ClientConfig.conventions()` at proposal-resolution time. The
`SumTypeMappingResolver` built in Step LLM-2 still has zero dependency on
`ClientConfig`, exactly as designed -- Step LLM-4 is where a
`ConfiguredVariantVocabulary`-style lookup gets consulted ahead of
canonical-name matching, with the `CONFIGURED_OVERRIDE_NOTABLE` problem
kind (already defined in `MappingResolutionProblem` since Step LLM-2)
finally getting used.

**Also noted, not acted on:** `canonical-models/SCHEMA.md`'s "Source
conventions" section documents `client` / `dateFormat` but was already
out of date before this step -- it doesn't mention `feeds:` either, a
real, shipped, tested Step 9 feature. Adding `conventions:` there too
would only be consistent with an already-stale section, not a complete
fix; left alone rather than partially patched, consistent with this
phase's own docs living in `docs/local-llm-enhancements.md` instead.

**Confirmed live.** Every claim above was written from a manual trace
against the real cloned source, not run in the authoring environment
(no Maven Central access there). Run against the real repo: **167/167**,
up from Step LLM-2's 148 by exactly the 19 new tests -- 7 in
`CanonicalModelRegistryTest`, 6 in `ClientConfigParserTest`, 6 in the new
`ClientConfigFingerprintTest` -- no other test count moved, first
attempt, no overlay ordering issue this time. Worth noting: every error
message the live run actually produced (the ambiguous-alias message, the
unknown-model message, the invalid-variant-target message) matched what
was written by hand character-for-character, which is real corroboration
the manual trace against `CanonicalModel`/`SumType`/`RecordType`'s actual
shapes was accurate, not just plausible-looking.

## Step LLM-4 build notes

**A real discovery that shrank this step's scope: mapping-memory
provenance was already done.** The roadmap line for this step listed
"extends mapping-memory provenance to include client-config version" as
a deliverable. Reading `MappingResolutionService` and
`ClientConfigFingerprint` before writing anything found this was already
true -- Step LLM-3 extended `ClientConfigFingerprint.hash()` to include
`conventions`, and `MappingResolutionService.resolve()` already threads
that fingerprint into `mappingMemoryRepository.findActiveMatch(...)`'s
lookup key. A client's convention changing already produces a different
fingerprint, which already produces a mapping-memory miss instead of a
stale hit -- no code change was needed here at all. Worth stating
explicitly rather than silently skipping: this is exactly the kind of
"read the real source first" discipline this whole phase has tried to
hold to, catching a planned deliverable that turned out to already be
satisfied by earlier work, rather than duplicating it.

**Precedence, as implemented in `SumTypeMappingResolver`.** For every
observed value being resolved (in the enrichment path *and* both
cross-check paths -- one shared `resolveValue` helper, not three
separate implementations that could drift):

```
observed value
    |
    v
configured vocabulary entry for this exact raw value?
    |
    +-- yes, target is a valid variant of this field
    |       -> use it (authoritative)
    |       -> if canonical-name matching would have produced a
    |          DIFFERENT variant, note it (CONFIGURED_OVERRIDE_NOTABLE,
    |          non-blocking) -- still uses the configured target
    |
    +-- yes, target is NOT a valid variant (stale config)
    |       -> CLIENT_CONFIGURATION (blocking)
    |       -> fails closed for this value; does NOT fall back to
    |          canonical-name matching
    |
    +-- no configured entry
            -> canonical-name matching (Step LLM-2's original
               three-tier exact/case-insensitive/normalized rule),
               unchanged
```

This matches the precedence settled during this phase's design
discussion exactly: an explicit, human-approved convention wins even
when it disagrees with what canonical-name matching alone would have
produced, and a stale convention fails closed rather than silently
falling back to a guess for a value the client explicitly configured.

**A real bug caught by tracing a test by hand before running anything.**
The first draft of `fillUnresolved` reused the existing
`ifPresentOrElse`-based loop unchanged, routing a `resolveValue` failure
into the same generic "unresolvedValues" bucket regardless of *why* it
failed. Tracing the `staleConfiguredTargetFailsClosed` test by hand
found this would produce **two** problems for one root cause: the
specific `CLIENT_CONFIGURATION` problem `resolveValue` itself already
adds, *and* a second, generic `UNRESOLVED` problem from the bucket
logic treating the same failure as if it were an ordinary unmatched
value. Fixed by tracking whether a failed value actually had a
configured entry (`configuredVocabulary.containsKey(value)`) -- if so,
the specific problem already explains it and the value is excluded from
the generic bucket; only a value with *no* configured entry that also
failed canonical-name matching goes into the generic "doesn't resolve"
message. This exact bug would not have been caught without deliberately
tracing a test's expected assertion (`hasSize(1)`) against the code path
by hand before treating the test as correct -- worth naming as a
pattern, matching how Step LLM-1's overlay-ordering bug was also only
caught by comparing an actual result against a specific, checkable
expectation, not by writing plausible-looking code and assuming it works.

**Not fully closed: the same double-reporting risk exists, unfixed, in
the two cross-check paths** (`validateSelectedVariant`,
`validateVariantValueMap`) -- if a value fails there specifically because
of a stale configured entry, both `resolveValue`'s `CLIENT_CONFIGURATION`
problem and the cross-check's own `SEMANTIC_CONFLICT` problem fire for
the same value. Deliberately left as-is rather than generalizing the
`fillUnresolved` fix everywhere: no test in this round exercises that
specific combination (a cross-check path colliding with a stale
configured entry), and the two-problems-for-one-cause outcome is
redundant but not actively wrong -- a human reading both would still
understand what's wrong. Worth revisiting if Step LLM-5's review UI ends
up needing single-cause clarity here.

**Non-blocking problems are now logged, not silently dropped.**
`AgentMappingProposalService.propose()` already filtered
`CONFIGURED_OVERRIDE_NOTABLE` out of the exception-triggering problem
list correctly since Step LLM-2 (it only ever collected `blocking()`
problems into that list) -- but nothing previously logged the non-blocking
ones anywhere, so between Step LLM-2 and this step they were completely
invisible, exactly the gap this phase's design discussion flagged
("produces zero signal"). Now logged at `INFO` on every occurrence, so an
operator (or, later, a log-scraping precursor to Step LLM-5's actual UI
affordance) has *something* to see before that UI exists.

**Field alias -> column matching before the LLM call: considered, not
built.** The roadmap phrased this as "considers whether," not a firm
deliverable, and this step keeps that framing. `ClientModelConventions.fieldAliases()`
is real, validated (Step LLM-3), and completely unused by any resolution
code as of this step. Wiring it in would mean a new, different kind of
resolver -- one that matches *source column headers* against configured
aliases to construct `FieldMapping` entries for *any* field kind
(primitive or sum type), not variant values within an already-identified
sum-type field, which is a meaningfully different piece of work from
what `SumTypeMappingResolver` does. Deliberately deferred rather than
bolted on to this step's scope creep-first instinct; a candidate for a
dedicated future step if it turns out to matter once Step LLM-6's
re-scoped benchmark shows how much LLM effort still goes into ordinary
column-name matching that configured aliases could resolve instead.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/SumTypeMappingResolver.java` (modified -- `resolve()` takes a new `ClientConfig client` parameter; configured vocabulary consulted via the shared `resolveValue` helper ahead of the original `matchVariant` three-tier fallback)
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- passes `client` through to the resolver; non-blocking problems now logged at `INFO`)
- `backend/src/test/java/com/alai/agenticsheets/mapping/SumTypeMappingResolverTest.java` (modified -- all 15 existing Step LLM-2 calls updated to pass a `noConventions()` client, preserving their original behavior exactly; 7 new Step LLM-4 tests appended)

**Tests added** (6 new, all in `SumTypeMappingResolverTest`):
- configured vocabulary fills a value that also happens to agree with
  canonical-name matching -> no `CONFIGURED_OVERRIDE_NOTABLE` noted
- configured vocabulary resolves a value canonical-name matching alone
  could never have matched (e.g. a short client-specific code like "FI")
- configured vocabulary deliberately diverges from what canonical-name
  matching would produce -> the configured target wins,
  `CONFIGURED_OVERRIDE_NOTABLE` recorded, non-blocking
- a stale configured target (not a real variant of the field) -> fails
  closed with a single `CLIENT_CONFIGURATION` problem, does not fall
  back to canonical-name matching, and does **not** double-report via
  the generic unresolved-values bucket (the bug found and fixed this
  round)
- configured vocabulary participates in the `selectedVariant` cross-check
  path too, not just the enrichment path
- a client with conventions configured for a *different* model falls
  through to pure canonical-name matching, unchanged from Step LLM-2

**Confirmed live.** `mvn test` run against the real repo after overlaying
these files: **173/173**, up from Step LLM-3's 167 by exactly the 6 new
tests, no other test count moved -- first attempt, no overlay ordering
issue. The hand-traced fix for the double-reporting bug (found before
this ever ran) held up: `staleConfiguredTargetFailsClosed` and every
other new test passed on the first real execution, not after a
correction round.

## Step LLM-5 build notes

**Scope decision, made before writing any code.** The roadmap's own
description ("approve a proposal -> optionally 'remember' a convention;
flat/tabular editor, not hand-edited YAML") implies three separable
pieces: (1) capturing a reviewer's "remember this" signal, (2) actually
folding that signal into `client-configs/<client>.yaml`, and (3) a
frontend affordance to trigger (1). This round builds (1) only, as a
real, tested backend API -- not a mock, not a stub. (2) and (3) are
deliberately deferred, for reasons specific to each, not just "ran out
of round." This mirrors how this project already split Step 8 into 8a
(backend groundwork) and 8b (the actual review screen), rather than
attempting both at once.

**Why (2) -- actually writing to the YAML file -- is deferred, and it's
not a small reason.** `client-configs/jpmc.yaml` is not a
machine-generated artifact; roughly half its content is human-written
explanatory comments (confirmed by rereading the actual file before
writing this reasoning down, not assumed). `CanonicalModelRegistry` treats
this file as the single, atomically-reloaded source of truth --
round-tripping it through a generic YAML writer (SnakeYAML, the library
this project already depends on for *reading* config) would very likely
silently discard every comment on the next write, since standard
YAML-emission libraries serialize the parsed data structure, not the
original document with its comments preserved. That's a real, concrete
risk to a file this project's own `mapping-notes.md` treats as carrying
load-bearing documentation, not just data -- worth stating plainly as
the reason this isn't built yet, rather than quietly deferred without
explanation. A comment-preserving YAML round-trip (or a switch to a
format/library that supports one) is real, separate work; building the
suggestion-capture API first, independent of that unsolved problem,
means (1) doesn't have to wait on it.

**Why (3) -- a frontend affordance -- is deferred.** This session has no
way to build or verify React changes: no `npm`/frontend build tooling
available in this sandbox, and (per this whole phase's established
practice) verifying a change means actually running it, not just writing
plausible-looking code. The backend API this round produces
(`POST /internal/mapping/proposals/{id}/suggest-convention`,
`GET /internal/mapping/convention-suggestions`,
`POST /internal/mapping/convention-suggestions/{id}/dismiss`) is a
complete, self-contained target for a future frontend pass -- a
"Remember this?" checkbox next to the review screen's existing
approve/reject controls, and a small admin-facing suggestions queue --
without that pass being blocked on anything backend-side.

**What was actually built.** A new `convention_suggestion` table --
captures one suggested fact (a `FIELD_ALIAS` or a `VARIANT_VALUE`) tied
back to the proposal a reviewer was looking at when they noticed it.
Deliberately does **not** write to `client-configs/*.yaml` (see above) --
this is the queue between "a reviewer noticed a pattern" and "an
administrator deliberately edits the YAML file," not an automatic
pipeline. `ConventionSuggestionService` validates a suggestion against
the *actual* canonical model (`CanonicalPaths`, the same utility
`SumTypeMappingResolver` and `MappingProposalStructuralValidator` already
use) before it's ever persisted -- the earliest point a mistake can be
caught, matching the same "verify at the earliest useful point"
discipline `ClientConventionsValidator` (Step LLM-3) already applies to
conventions that have actually made it into a YAML file. Three new
endpoints on `MappingController`, alongside the existing
approve/reject/amend actions, since this is fundamentally a
review-workflow action, not a config-registry-inspection one (`/internal/canonical`'s existing controller is read-only by design).

**A real race avoided by following this project's own established
lesson, not by accident.** The first design instinct for
"suggesting the same fact twice shouldn't create two rows" was a
check-then-insert (`findPending`, then insert if absent) in application
code. Catching this before writing it: that's exactly the
check-then-act race this project's own Step 6.1/7.3 hardening rounds
found and fixed for `import_batch` -- two concurrent reviewers
suggesting the same fact could both observe "nothing pending yet" and
both insert. Used the same fix this project already proved out instead:
a partial unique index (`uq_convention_suggestion_pending`, only over
`PENDING` rows -- a dismissed suggestion must not block a fresh one for
the same fact) plus `INSERT ... ON CONFLICT ... DO NOTHING RETURNING id`,
the identical idiom `ImportBatchRepository.findOrCreate` already uses.
Proven directly with a Testcontainers test
(`suggestingTheSameFactTwiceReturnsTheSameRowNotADuplicate`), not just
reasoned about.

**Files changed:**
- `db/init/01-orchestration-schema.sql` (modified -- new `convention_suggestion` table, `idx_convention_suggestion_client_status`, `uq_convention_suggestion_pending`)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ConventionSuggestion.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ConventionSuggestionRepository.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ConventionSuggestionService.java` (new)
- `backend/src/main/java/com/alai/agenticsheets/mapping/MappingController.java` (modified -- three new endpoints, a new `SuggestConventionRequest` record, a new `IllegalArgumentException` -> 400 handler)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ConventionSuggestionRepositoryTest.java` (new -- Testcontainers-backed, 7 tests)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ConventionSuggestionServiceTest.java` (new -- mocked repositories, real canonical model, 9 tests)

**Tests added** (16 new):
- `ConventionSuggestionRepositoryTest` (real Postgres): a new suggestion
  is created PENDING; suggesting the identical fact twice returns the
  same row, not a duplicate (the race-avoidance proof above); dismissing
  then re-suggesting the same fact creates a fresh row (the partial
  index correctly scopes to PENDING only); dismissing sets status and
  `resolved_at`; dismissing an already-resolved suggestion fails;
  client-scoped filtering by status is correct and doesn't leak across
  clients; `findPending` returns empty when nothing matches.
- `ConventionSuggestionServiceTest` (mocked repositories, real
  `holdings.yaml`): valid `FIELD_ALIAS` and `VARIANT_VALUE` suggestions
  are persisted with the right arguments; a field-alias referencing an
  unknown field path is rejected; a variant-value suggestion on a
  non-sum-type field is rejected; a variant-value mapping to an invalid
  variant name is rejected; a variant-value suggestion missing its
  target is rejected; a field-alias suggestion that sets a target
  (structurally contradictory) is rejected; an unknown `kind` is
  rejected; a blank `sourceValue` is rejected.

**Confirmed live -- with a real failure caught and fixed, not a clean
first pass this time.** `mvn test` run against the real repo: 187 of the
189 total passed; `ConventionSuggestionServiceTest.validVariantValueSuggestion_isPersisted`
threw a `NullPointerException` (`Cannot invoke "java.lang.Long.longValue()"`),
and the very next test in the same class,
`variantValueMissingTargetVariant_rejected`, failed too with an
unrelated-looking `InvalidUseOfMatchersException`.

Root cause, one bug, two symptoms: `ConventionSuggestionRepository.suggest`'s
first parameter is a primitive `long`
(`sourceProposalId`), not a boxed `Long`. The broken stub used Mockito's
generic `any()` for every one of the eight arguments, including that
primitive position -- `any()` returns `null`, and `null` cannot be
unboxed to a primitive `long`, hence the `NullPointerException` on the
very first test that hit it. Mockito's internal matcher stack doesn't
cleanly recover from a stubbing call that throws mid-registration, so
the *next* test method's legitimate matcher usage got corrupted by the
previous test's leftover unconsumed matcher state -- a real example of
one root cause producing what looks like two unrelated failures, worth
naming explicitly since it could easily read as "two bugs" and send
troubleshooting in the wrong direction. Fixed by using `anyLong()` for
the primitive position specifically (`any()` remains correct for the
other seven, all reference-type `String` parameters) -- confirmed no
other Mockito stub in either new test file made the same mistake by
checking every `when(...)`/`verify(...)` call in both files by hand,
not just the one that actually failed.

This is a genuine example of the exact thing this whole phase has kept
finding: a plausible-looking test that would have been wrong if trusted
without actually running it. The fix is a one-line, two-import change
(`ConventionSuggestionServiceTest.java` only -- no production code was
at fault) -- but it has **not** yet been re-run. The 189/189 count below
is what the fix should produce given the fault was isolated to exactly
that one stub, not a confirmed result; that claim needs the same
scrutiny as every other "should work" claim in this phase, not an
exception because the bug and fix both feel small.

**Not run in this environment, but the fix has now been confirmed.** The
failure account above describes what actually happened when `pramalin`
ran `mvn test` locally the first time: 187 of 189 passed, 2 failed for
the one identified cause. After the `anyLong()` fix, a second real run
confirmed **189/189**, all passing -- the fix genuinely resolved the
NPE and the matcher-stack corruption it caused, and introduced nothing
new. Per this whole phase's established pattern, a Testcontainers test
is exactly the kind of thing most likely to reveal something a manual
trace couldn't catch (real SQL, a real partial index, a real
constraint) -- and this round is a reminder that a *mocked* test can
just as easily hide a real bug, so "needs a real database" isn't the
only category of test worth actually running before trusting it.

**What Step LLM-5b would need, if and when it's picked up:** a
comment-preserving YAML write path (or an accepted decision to drop
comments, made deliberately rather than discovered by accident), an
"apply" endpoint that actually edits `client-configs/<client>.yaml` and
triggers `CanonicalModelRegistry.reload()`, and the frontend affordance
described above. None of that is blocked by anything built this round --
the suggestion-capture API is a complete, stable target either way.

## Step LLM-6

Fixture and runner prepared; the actual benchmark run against real
hardware has not happened yet -- this environment has no GPU, no
Ollama/Docker Model Runner, and no way to pull or run model weights, so
this step's output is a ready-to-run harness, not a result. Testing is
planned to start with `qwen2.5:3B-Q4_K_M` (matching
`compose.local-llm.yaml`'s own default, so no config change is needed to
run it) before moving on to larger models or DGX Spark hardware.

**Fixture.** `sample-input/holdings_jpmc_llm6_unfamiliar_column.xlsx` --
byte-identical to the real `holdings_jpmc_20260115.xlsx` fixture except
for exactly one cell: the `Price` header renamed to `Valuation Px`.
Verified programmatically (not by eye) that this is the *only* cell that
differs between the two files before committing it. Every other column,
including `Currency` and `Asset Class` -- the two fields this entire
phase was built around -- is untouched, and neither `Valuation Px` nor
any variant of it appears anywhere in `jpmc.yaml`'s `fieldAliases` or
this codebase generally. This isolates exactly the question Step LLM-6
exists to ask: with known facts resolved deterministically, can a small
model correctly resolve one genuinely unfamiliar column on its own,
rather than testing whether it can reconstruct an entire mapping it may
have partially memorized from repeated identical fixture runs.

**Runner.** `scripts/local-llm/run-llm6-benchmark.sh` -- reuses the
existing `run-holdings-proposal.sh` unchanged (deliberately: minimal
surface area, no risk of the harness itself introducing a variable),
run once against the original fixture (re-establishing today's baseline
-- see below) and once against the unfamiliar-column fixture, with
clearly labeled console output for each so the two runs' results can't
be confused with each other in `build/local-llm-results/`.

**A stale claim caught and corrected while writing this.**
`scripts/local-llm/README.md` still said, before this round, that a
Qwen 2.5 3B proposal against the original fixture is *expected* to be
structurally rejected (curl exit 22) -- true when that note was
written, before Steps LLM-1 through LLM-4 existed, and very likely
false now: the whole point of those steps was to make the resolver fill
in exactly the currency/asset_class gaps that caused that rejection.
Left as a claim to *reconfirm*, not silently updated to a new assumed
answer -- the runner's first fixture is the original file specifically
so this gets re-tested for real before Step LLM-6's actual novel-column
question is asked, rather than assuming the old baseline still holds.

**What "success" looks like, stated before running it, not after.**
`curl` exiting 0 means the proposal passed structural validation and
this phase's deterministic resolver -- it does **not** by itself mean
`Valuation Px` was correctly mapped to `market_price`. Three genuinely
different, all-legitimate outcomes are possible and worth distinguishing
when the results come back: the model correctly proposes
`Valuation Px -> market_price`; the model leaves it in
`unmappedSourceColumns` (a correct refusal to guess, not a failure --
exactly the fail-closed behavior this whole phase has valued over a
confident wrong guess); or the model proposes something wrong. Only the
last of those is actually a bad outcome.

### First real run, and what it found

Run against `qwen2.5:3B-Q4_K_M`, CPU-only, matching
`compose.local-llm.yaml`'s own default -- no config change needed.

**Baseline (`holdings_jpmc_20260115.xlsx`): a real, confirmed success.**
HTTP 200 in 3:13.97. The returned proposal correctly resolves `currency`
to `selectedVariant: "USD"` and `asset_class` to a complete
`variantValueMap` (`Equity -> Equity`, `Fixed Income -> FixedIncome`) --
exactly the two fields that reliably broke every model in the original
3B/7B/14B/32B benchmark (`docs/local-llm-evaluation.md`). This
definitively retires `scripts/local-llm/README.md`'s old claim that a
Qwen 2.5 3B proposal against this fixture is expected to be structurally
rejected -- that was true before Steps LLM-1 through LLM-4 existed, and
is now confirmed false, not just presumed false.

**One real methodology gap this run exposed: the benchmark can't yet
tell "the model got it right" from "the resolver quietly fixed it."**
The response above is the *post-resolution* proposal. Nothing currently
distinguishes what Qwen actually produced from what
`SumTypeMappingResolver` filled in afterward -- both converge to the
same final JSON by design, since that convergence is the entire point of
Steps LLM-1 through LLM-4. For Step LLM-6's actual question (how much
LLM capability is still needed once known facts are handled
deterministically), that distinction matters and isn't visible yet.
Worth fixing before drawing a strong conclusion from this baseline
result -- see "not yet done" below.

**Unfamiliar-column run: a real bug, not a model finding.** HTTP 500,
not the expected clean-or-declined outcome:

```
NullPointerException: Cannot invoke "java.util.List.stream()" because
the return value of "com.alai.agenticsheets.mapping.MappingProposal.fieldMappings()"
is null
```

A real, previously-unexercised gap in this phase's own code, not the
model being wrong: nothing before this run had ever passed
`SumTypeMappingResolver.resolve()` a proposal decoded from *malformed*
model output. Every one of this phase's own tests, across every step,
always hand-constructed proposals with `List.of(...)` for
`fieldMappings` -- never `null`. Faced with a column
(`Valuation Px`) it apparently couldn't confidently handle, the 3B model
produced something that decoded to `fieldMappings: null` entirely
(plausibly: a smaller model under CPU-only inference truncating or
malforming its structured-output JSON when genuinely unsure, though the
raw model response itself wasn't captured to confirm the exact
mechanism -- worth logging next time this happens). `SumTypeMappingResolver.resolve()`'s
very first line (`proposal.fieldMappings().stream()...`, written back in
Step LLM-2) crashed on exactly that, leaking a raw, unhelpful 500
instead of the clean, reported validation failure a malformed proposal
should always produce.

**Fix.** Two changes, together closing this for good rather than
patching just the one call site that happened to crash first:

1. `MappingProposal` gained a compact constructor normalizing a `null`
   `fieldMappings` or `unmappedSourceColumns` to an empty list --
   regardless of which path constructs a `MappingProposal` (Spring AI's
   structured-output decode, JSONB deserialization of an
   already-persisted proposal, or a hand-built `/amend` request body).
   Grep confirmed **five** separate call sites across four classes
   (`MappingProposalStructuralValidator`, `SumTypeMappingResolver` --
   twice, `ProposalValidationService`, `MappingMemoryEligibility`) all
   assumed non-null `fieldMappings` without ever checking; normalizing
   once at the type boundary closes all of them at once rather than
   requiring every current and future call site to remember to guard
   itself, which is exactly how this gap existed in the first place --
   `SumTypeMappingResolver` alone had two separate unguarded uses,
   written in the same step, by the same author, and still missed.
2. `MappingProposalStructuralValidator.validate()` gained an explicit
   check: an empty `fieldMappings` list is now reported as its own
   problem ("the proposal contains no field mappings at all -- likely
   malformed or truncated model output, not a legitimate empty
   mapping"), rather than silently passing. This mattered independently
   of the crash: the validator only ever inspects entries that exist in
   the list, so before this fix, simply normalizing `null` to `[]`
   (change 1 alone) would have silently *replaced a loud crash with a
   silently-accepted, structurally-"valid" proposal that maps nothing*
   -- persisted to the review queue with no signal to a reviewer about
   why, only surfacing confusingly later (every row failing every field)
   at `/approve` time. Worse than the crash it would have replaced, not
   better. Explicitly checking for and reporting the empty case was the
   actual fix; the compact constructor is what makes that check reliably
   reachable instead of an NPE getting there first.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/MappingProposal.java` (modified -- compact constructor)
- `backend/src/main/java/com/alai/agenticsheets/mapping/MappingProposalStructuralValidator.java` (modified -- explicit empty-list check)
- `backend/src/test/java/com/alai/agenticsheets/mapping/MappingProposalTest.java` (new -- 4 tests for the compact constructor in isolation)
- `backend/src/test/java/com/alai/agenticsheets/mapping/MappingProposalStructuralValidatorTest.java` (modified -- 2 new tests: empty list reported, null-from-constructor normalized then reported the same way)

**Confirmed live.** `mvn test` run against the real repo after
overlaying these files: **195/195**, up from Step LLM-5's 189 by exactly
the 6 new tests, no other test count moved.

**Not yet done:** re-run `run-llm6-benchmark.sh` against the fix to see
whether the unfamiliar-column question actually gets answered this
time (correct mapping, correct decline, or wrong mapping -- all three
remain legitimate, distinguishable outcomes, per the "what success looks
like" note above). Also still open: the methodology gap noted above --
distinguishing what the model itself proposed from what the resolver
filled in, which would need the raw pre-resolution proposal logged or
returned alongside the resolved one, not just the final result. Neither
is blocked by this fix; both are natural next steps once it's applied.

### Raw model response logging, and a second null-safety guard

Both natural next steps noted above got picked up in the same round,
prompted directly by the confusing empty-mapping result (see above): the
final decoded proposal was visible, but nothing about what the model had
actually generated before decoding was -- no way to tell truncation from
a genuinely empty response from a prose refusal instead of JSON.

**Checked against Spring AI's own API before writing anything, not
guessed at.** The obvious-looking approach -- call `.content()` for the
raw text and `.entity(Class)` for the parsed proposal, on the same
response spec -- is a real, documented Spring AI pitfall (confirmed
against the framework's own GitHub issue tracker): calling two separate
terminal methods on one `CallResponseSpec` triggers two separate model
invocations, not one shared result. Using both would have silently
doubled every real inference call this benchmark makes, and risked the
logged raw text and the entity actually used coming from two different
generations. The correct, single-call API -- confirmed directly against
Spring AI 2.0.0's own javadoc, not a blog post or a guess -- is
`responseEntity(Class<T>)`, returning `ResponseEntity<ChatResponse, T>`
with both the raw `ChatResponse` and the converted entity from the exact
same call.

**What changed.** `AgentMappingProposalService.propose()` now calls
`.responseEntity(MappingProposal.class)` instead of `.entity(MappingProposal.class)`,
logs the raw text (`chatResponse.getResult().getOutput().getText()`,
also confirmed against Spring AI's documented API rather than assumed)
at `INFO` -- not `DEBUG`: this project's default log level
(`application.yml`) is `INFO`, and the whole point is for this to
actually show up in a normal `docker compose logs` run, not require
remembering to turn on debug logging first -- and defends against
`entity()` itself returning `null` (Spring AI's own javadoc: "the
deserialized entity, or null if the response is empty"), a broader,
one-level-up version of the `fieldMappings: null` case this same round
already fixed. A null entity is now replaced with
`new MappingProposal(null, null, null)`, which -- via the compact
constructor from this round's earlier fix -- normalizes to empty lists
and flows through the exact same clean, reported "no field mappings at
all" validation failure as before, rather than crashing two lines later
on a null `MappingProposal` reference itself.

**Deliberately not unit-tested with a mocked `ChatClient`.** Mocking
Spring AI's fluent builder chain (`.prompt().system().user().call().responseEntity(...)`)
deeply enough to exercise this change would prove only that the mock
does what the mock was told to do -- not that the real Spring AI API
actually behaves the way its javadoc says, which is the exact thing
worth being careful about here, given the double-invocation pitfall this
change exists specifically to avoid. That's not a gap to quietly leave
unmentioned: real confirmation is the full test suite (proving nothing
else broke) plus the next actual benchmark run against live Docker Model
Runner infrastructure, the same standard the rest of this phase has held
to throughout rather than trusting a plausible-looking mock.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- `responseEntity()` instead of `entity()`, raw-text logging, null-entity guard)

**Not run in this environment** -- no test count changed by this
specific piece (see above for why), so the confirmed 195/195 from the
`MappingProposal`/`MappingProposalStructuralValidator` fix still stands
as the last actual test-suite confirmation. This piece specifically
needs a real benchmark re-run to confirm, not `mvn test`.

### Clearing state between benchmark runs

The first re-run after the null-safety fix reused the original
baseline's cached batch/proposal from Postgres (same `content_hash` ->
`import_batch`'s own dedupe -- see that table's schema comment) rather
than making a fresh model call, completing in 0.10s instead of the
several minutes a real CPU inference call takes. Not a bug -- exactly
`MappingController.propose()`'s documented fast path -- but worth
clearing deliberately before a benchmark run where repeated, independent
model calls are the actual point:

```bash
docker compose -f compose.yaml -f compose.local-llm.yaml down -v
docker compose -f compose.yaml -f compose.local-llm.yaml up -d --build --wait
```

`-v` removes the named volumes declared in `compose.yaml`'s own
top-level `volumes:` section (`postgres-data`) -- deliberately not
naming the actual prefixed Docker volume directly (e.g.
`agentic-sheets_postgres-data`), since Compose's own project-name
prefixing isn't this project's concern to hardcode into a doc that could
drift from whatever the actual project name resolves to on a given
machine. This clears every table (`import_batch`, `mapping_proposal`,
`mapping_memory`, `convention_suggestion`, everything) back to
schema-only, empty, exactly like a first-ever run -- worth knowing
before using it against anything other than a disposable local benchmark
environment.

### Second real run: both open questions answered, one important, one unexpected

Volume cleared, stack rebuilt, both fixtures run fresh against
`qwen2.5:3B-Q4_K_M` with raw-response logging active.

**Finding 1 -- the methodology gap is closed, and the answer is exactly
what this phase's premise predicted.** The raw model text for the
baseline run shows `currency`'s `selectedVariant` as `null` and
`asset_class`'s entry with no `variantValueMap` key at all -- the model
left both genuinely unresolved, identical to the original pre-Step-LLM-1
benchmark's finding. The clean `selectedVariant: "USD"` and complete
`variantValueMap` in the final response the earlier run showed were
never the model's own output; `SumTypeMappingResolver` filled them in
silently, exactly as designed. This is now a confirmed fact from real
raw model output, not an inference from the final JSON: **Qwen 2.5 3B
still cannot resolve these sum-type fields on its own, and the
deterministic layer this whole phase built is doing precisely the work
it exists to do.** This is the strongest evidence yet for this phase's
central premise, and it was only possible to state this confidently
because of the raw-logging change -- before it, this exact question was
explicitly listed as unanswerable from the final response alone.

**Finding 2 -- the unfamiliar-column failure is not a graceful decline,
and knowing that matters.** The raw text for that run is not truncated,
not a refusal, not a wrong guess. It is the JSON Schema Spring AI's
structured-output converter injects into the prompt as formatting
instructions, echoed back nearly verbatim (`"$schema"`, `"type": "object"`,
`"properties"`, `"required"`, `"additionalProperties": false`) as if that
schema metadata were the response itself -- `fieldMappings` ends up
holding the schema's own array-type definition, not actual mapping data,
while `summary` and `unmappedSourceColumns` are filled with plausible,
real-looking values. A genuinely distinct failure mode from either
"correctly declines to guess" (the good outcome originally hoped for) or
"guesses wrong" (the bad outcome originally anticipated): under whatever
confusion the unfamiliar column triggered, the model's structured-output
generation broke down and it fell back to reproducing visible context
from its own prompt rather than generating a genuine instance. Worth
naming precisely rather than lumping it in with either anticipated
outcome, since the fix for "model panics and echoes its own
instructions" is not the same fix as "model guesses wrong" or "model
correctly abstains."

**The system's own handling of this worked exactly as designed, at
every layer this phase built.** No crash (the `NullPointerException`
fix). No silent acceptance of garbage (the explicit empty-`fieldMappings`
check, which is what actually caught this -- the schema-echo response
technically has `fieldMappings` present as a JSON key, but Spring AI's
binding of that malformed structure back to `MappingProposal` evidently
produced an empty or null list all the same, since the same "no field
mappings at all" message fired). A clean 422 with an accurate message,
not a confusing empty-but-200 result. Three deliberately-built layers,
all doing their job on a real, previously-unseen failure mode none of
them were specifically designed around.

**What this suggests for next steps, not yet acted on:** the schema-echo
failure mode is worth checking for repeatability (same unfamiliar
column, fresh run, does it recur or was this one generation's fluke) and
worth testing against a larger model (7B/14B, or DGX Spark hardware) to
see whether it's a 3B-specific breakdown under CPU-only inference or
something more structural in how the prompt/schema is presented. Neither
run yet; both are natural continuations of this step, not separate new
work.

## External review, round 1: two correctness bugs

After Step LLM-6's real findings, an external review of the cumulative
phase (not commit-by-commit) found seven issues. Two -- both genuine
correctness bugs, not style preferences -- are fixed this round; the
other five (a hash-collision risk in `ClientConfigFingerprint`, raw
model logging that should default to opt-in rather than always-on,
convention-suggestion conflicts that silently collapse, a framing
caveat about what Step LLM-6 does and doesn't yet isolate, and the
benchmark script's inability to detect a cache-hit result) are agreed
with and deliberately deferred to a second pass rather than attempted
alongside these two -- reasoning below.

**Bug 1: `variantValueMap` was only checked for coverage, never for
semantic agreement.** `validateSelectedVariant` already ran every
observed value through `resolveValue` (configured vocabulary, then
canonical-name matching) and flagged a mismatch. `validateVariantValueMap`
never did the equivalent check on its own *targets* -- only whether
every observed value had a key at all. The review's example is exact
and was verified by hand-tracing the old code before touching it: an
authoritative client convention `USD -> USD` would not have caught a
model proposing `variantValueMap={"USD":"EUR"}`, since `EUR` is itself a
legal variant and nothing ever compared the model's chosen target
against what `resolveValue` would have independently produced. Fixed:
each observed value's proposed target is now compared against
`resolveValue`'s result, but *only* when `resolveValue` actually
produces a deterministic answer to disagree with -- an ambiguous or
unresolvable value has no known-correct answer, so the model's own
mapping is left as proposed for human review rather than flagged wrong
against nothing, exactly matching the review's own recommended fix.

**Bug 2: a `null` element within a non-null `fieldMappings` list wasn't
handled, even after this round's earlier null-safety fix.** `MappingProposal`'s
compact constructor (from the `NullPointerException` fix earlier this
step) normalizes a `null` *list reference* to empty -- it was never
about individual *elements* within an otherwise real list. Given the
schema-echo finding already proved a confused 3B model can produce
genuinely unexpected structured output, `fieldMappings: [null, {...}]`
is a real, not hypothetical, risk the first fix didn't cover. Fixed in
two places: `SumTypeMappingResolver` now skips a `null` element without
crashing (passing it through unchanged, matching how it already treats
an empty list -- no crash, no report at that layer), and
`MappingProposalStructuralValidator` -- the one place both `propose()`
and `validateEdited()` funnel through -- now explicitly reports a `null`
element as its own problem, the same "verify at the earliest shared
point" pattern the empty-list check already established.

**A third piece of the same review, addressed as a code fix without a
matching test, explained plainly rather than silently skipped.** The
review separately asked what happens if Spring AI's structured-output
conversion *throws* rather than returning a `null` entity -- a case
this project had genuinely never handled, since only the documented
"empty response" case (`entity()`'s own javadoc) had been defended
against. `AgentMappingProposalService.propose()` now wraps the model
call in a `try/catch (RuntimeException)`, treating a thrown exception
identically to a `null` entity -- the same clean, reported validation
failure, not a raw 500. No test accompanies this specific change: this
project has zero existing test infrastructure for `AgentMappingProposalService`'s
actual model-interaction code (confirmed by grep, not assumed --
`MappingResolutionServiceTest` only ever mocks the whole service by its
public method signature), and a real integration test would need a
custom test `ChatModel` bean, full Spring context wiring, and fixture
setup for `CanonicalModel`/`ClientConfig`/`JsonNode` table data largely
from scratch. That's legitimate, separate work worth doing properly,
not squeezed into an already-large round -- deferred explicitly, not
quietly dropped.

**Why the other five findings weren't attempted in this same pass.**
All five are agreed with on their merits (see the response given at the
time this review arrived, preserved in this project's own conversation
history rather than restated here) -- none were dismissed. They're
deferred specifically to keep this pass small enough to verify
confidently: a database schema change and re-hash of an existing safety
mechanism (the fingerprint collision), a new configuration property
touching a cross-cutting concern (opt-in raw logging), a new failure
path and HTTP status through an already-shipped API
(convention-suggestion conflicts), and a benchmark script change (cache-hit
detection) are each independently scoped, independently testable
changes -- bundling all seven findings into one round risks exactly the
kind of sprawling, harder-to-verify change this whole phase has tried
to avoid throughout.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/SumTypeMappingResolver.java` (modified -- `validateVariantValueMap` now semantically checks targets, not just coverage; null-element handling in the main resolution loop and the `hasSumTypeMapping` check)
- `backend/src/main/java/com/alai/agenticsheets/mapping/MappingProposalStructuralValidator.java` (modified -- explicit null-element check, reported not crashed)
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- `try/catch` around the model call, treating a thrown exception the same as a null entity)
- `backend/src/test/java/com/alai/agenticsheets/mapping/SumTypeMappingResolverTest.java` (modified -- 6 new tests: the review's exact `USD -> EUR` example, agreement/no-conflict regression coverage, "no deterministic answer, left alone" behavior, stale-config-not-double-reported, and two null-element tests)
- `backend/src/test/java/com/alai/agenticsheets/mapping/MappingProposalStructuralValidatorTest.java` (modified -- 2 new tests: a null element is reported not crashed, and doesn't prevent other real entries in the same list from being checked)

**Tests added** (8 new total):
- `variantValueMapDisagreesWithAuthoritativeConfiguredVocabulary_semanticConflict`
  -- the review's own `USD -> EUR` example, verified to produce exactly
  one blocking `SEMANTIC_CONFLICT`, proposal left unrepaired
- `variantValueMapAgreesWithCanonicalMatching_noConflict` -- regression
  coverage confirming the existing correct-mapping case still passes clean
- `variantValueMapValueWithNoDeterministicAnswer_leftForHumanReview` --
  an unresolvable value's model-proposed target is not flagged, matching
  the review's own recommended semantics exactly
- `variantValueMapMismatchAgainstStaleConfiguredEntry_reportsConfigProblemNotMismatch`
  -- confirms a stale configured entry reports as `CLIENT_CONFIGURATION`
  only, not doubled up with a misleading mismatch problem
- `nullFieldMappingElement_doesNotCrashTheResolver` /
  `nullFieldMappingElement_doesNotBreakHasSumTypeMappingDetection` --
  resolver-side null-element handling
- `nullFieldMappingElement_reportedNotCrashed` /
  `nullFieldMappingElement_doesNotPreventValidEntriesFromBeingChecked` --
  validator-side null-element handling, including alongside a genuinely
  invalid real entry in the same list

**Confirmed live.** `mvn test` run against the real repo after
overlaying these files: **203/203**, up from 195 by exactly the 8 new
tests, no other test count moved, no regressions.

## External review, round 2: the remaining four findings

All four were agreed with on their merits when the review first arrived
(see round 1's own notes above for the full per-finding reasoning);
deferred there specifically to keep each round independently verifiable
rather than bundling seven changes into one. This round closes them out.

**Fingerprint collision, fixed at the root rather than patched at the
symptom.** The original `ClientConfigFingerprint` concatenated sorted
values with hand-picked delimiters (`,`, `=`, `|`, `->`). Verified the
review's claim by hand before touching anything: alias lists
`["a,b", "c"]` and `["a", "b,c"]`, both sorted, both concatenated to the
identical string `"a,b,c,"` -- a real collision, not a hypothetical one,
in the exact mechanism built to guarantee two different configurations
never hash the same. Escaping the specific delimiters found would have
fixed that one case; it wouldn't have fixed the general problem, since
any future alias or variant-value text containing a not-yet-anticipated
character could reopen it. Rewritten instead to build a canonical,
fully-`TreeMap`-sorted structure and serialize it through `JsonMapper`
(the same library `MappingProposalRepository` already uses for JSONB
persistence) -- real JSON quoting handles arbitrary string content
correctly by construction, which is a structurally stronger guarantee
than trying to anticipate and escape every delimiter a client's own
text might someday contain. Two adversarial tests added, mirroring the
review's own example plus the equivalent case on the variant-values
serialization path.

**Raw model logging made opt-in.** `agentic-sheets.log-raw-model-response`
(env var `AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE`), default `false`,
following this project's established `@Value`-with-default constructor
pattern (matching `FileHasher`, `InboxScanner`, `ApiKeyAuthFilter`, and
others). When disabled, a `DEBUG`-level breadcrumb (character count
only, never content) still confirms a response was received, without
ever logging what it said by default. The capability that made Step
LLM-6's real findings possible is entirely preserved -- it's an opt-in
switch, not a removal.

**Convention-suggestion conflicts now surfaced, not silently
collapsed.** `uq_convention_suggestion_pending` deliberately excludes
`target_variant` from its uniqueness (two reviewers independently
suggesting the *same* fact should confirm it, not duplicate the row) --
but that also meant a genuinely *different* target for the same source
value hit the identical conflict target and silently returned whichever
row got there first. `ConventionSuggestionRepository.suggest()` now
checks the *existing* row's target against the *new* one after a
conflict: agreement is still treated as idempotent confirmation
(unchanged behavior); disagreement now throws `IllegalStateException`,
which `MappingController`'s existing handler already maps to HTTP 409 --
no new exception type or handler needed, since this is exactly the
"already in a conflicting state" condition that handler already exists
for. Both the schema comment on the index and this method's own javadoc
now explain why `target_variant` is deliberately excluded from the
*index* while still being checked in *application code* -- worth
stating plainly so a future reader doesn't "fix" the index by adding it
back and silently reintroduce the duplicate-row problem the exclusion
was solving.

**Benchmark script now warns on a suspiciously fast run, never acts.**
`run-llm6-benchmark.sh` parses the elapsed time `run-holdings-proposal.sh`
already captures and warns (loudly, but takes no action) if a run
completes under `CACHE_HIT_THRESHOLD_SECONDS` (default 10, overridable).
Verified the parser against the actual real elapsed times observed
across this whole step -- `3:13.97`, `1:38.05`, `0:00.10`, and a
synthetic `1:02:03.45` for the over-an-hour case -- all four parse to
the correct whole-second count. Deliberately does not clear the
Postgres volume automatically, matching the review's own explicit
caution: a benchmark script silently destroying state is a worse
failure mode than an occasional false "this might be cached" warning.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/ClientConfigFingerprint.java` (rewritten -- canonical JSON serialization via `JsonMapper` instead of delimiter concatenation)
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- opt-in raw-response logging via new `@Value`-injected constructor parameter)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ConventionSuggestionRepository.java` (modified -- `suggest()` now distinguishes a same-target conflict from a different-target one)
- `backend/src/main/resources/application.yml` (modified -- new `agentic-sheets.log-raw-model-response` property, default `false`)
- `db/init/01-orchestration-schema.sql` (modified -- `uq_convention_suggestion_pending`'s comment now explains the deliberate `target_variant` exclusion and where the corresponding application-code check lives)
- `scripts/local-llm/run-llm6-benchmark.sh` (modified -- cache-hit detection, warning-only)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ClientConfigFingerprintTest.java` (modified -- constructor updated for the new `JsonMapper` dependency; 2 new adversarial collision tests)
- `backend/src/test/java/com/alai/agenticsheets/mapping/ConventionSuggestionRepositoryTest.java` (modified -- 2 new tests: conflicting-target throws, same-target stays idempotent)
- `backend/src/test/java/com/alai/agenticsheets/mapping/MappingResolutionServiceTest.java` (modified -- `ClientConfigFingerprint` construction updated for the new dependency; no behavior change, no new tests)

**Tests added** (4 new):
- `adversarialDelimiterCollisionInFieldAliases_stillHashesDifferently` --
  the review's own exact example, now hashing differently
- `adversarialDelimiterCollisionInVariantValues_stillHashesDifferently`
  -- the equivalent adversarial pair on the other serialization path
  (`{"A->B":"C"}` vs `{"A":"B->C"}`, both old-style concatenating to
  `"A->B->C,"`)
- `conflictingTargetForSameSourceValue_throwsRatherThanSilentlyReturningTheFirstRow`
  -- the review's own `USD -> USD` then `USD -> EUR` example, now a 409
- `sameTargetForSameSourceValue_stillIdempotentNotAConflict` -- regression
  coverage confirming the legitimate confirmation case is unaffected

**Confirmed live.** `mvn test` run against the real repo after
overlaying these files: **207/207**, up from round 1's 203 by exactly
the 4 new tests, no other test count moved, no regressions.

**What remains from the original review, now genuinely all addressed
except the one deliberately-scoped architectural item:** the framing
caveat (Finding 6 -- Step LLM-6 doesn't yet isolate a single ambiguity,
since `fieldAliases` remains unused in resolution) was never a bug to
fix, and stands as accurately stated in this doc's own Step LLM-6
section already. Deterministic field-alias/header resolution before the
LLM call remains the next real architectural step, not something either
review round attempted to squeeze in alongside these seven fixes.

## Field-alias resolution: the deferred architectural step, finally built

The one item both review rounds explicitly left open. Two independent
sources of deterministic column-naming knowledge already existed in
this codebase, both previously used only as *hints* rendered into the
LLM's own prompt, never consulted deterministically: a canonical
field's own name, `CanonicalModel.synonyms` (client-agnostic, curated
per model -- Holdings' real `synonyms:` block, read directly before
building anything, already covers every primitive field: `cusip`/`isin`/`sedol`
for `security_id`, `price`/`mkt price` for `market_price`, and so on),
and `ClientModelConventions.fieldAliases` (client-specific, Step LLM-3).

**What changed.** A new `FieldAliasResolver` merges all three sources
(a field's own name, its canonical synonyms, its client's configured
aliases) into one flat, normalized lookup, with ambiguity detection
that fails closed exactly like every other deterministic resolver in
this phase. `AgentMappingProposalService.propose()` now runs this
*before* building the prompt: resolved columns are removed from the
table the model actually sees, an explicit note lists what's already
handled, and the deterministic results are merged back in after the
model responds. This is what makes the "known headers -> deterministic,
known vocabulary -> deterministic, one unresolved column -> LLM"
architecture literally true, rather than a model that still
reconstructs the entire column-to-field mapping with only sum-type
mechanics backstopped -- the second review round's own Finding 6.

**A real, load-bearing gap found and closed while building this, not
after.** `CanonicalModel.synonyms` keys were never validated against
real field paths at parse time -- harmless while synonyms were purely a
prompt hint (a typo just meant a slightly worse hint), a genuine
correctness risk now that they're load-bearing for deterministic
resolution a resolver actually trusts. Fixed at `CanonicalModelRegistry`
load time, the same place client conventions are already validated
against the real parsed model. Verified both real production canonical
model files (`holdings.yaml`, `market_rate_book_value.yaml`) pass this
new check cleanly by reading their actual `synonyms:` blocks against
their actual field definitions before writing the validation, not
assuming.

**A small, deliberate refactor along the way.** The "what are this
model's valid field paths" question now has a third consumer (synonym
validation, alongside `ClientConventionsValidator` and, in the
`mapping` package, `CanonicalPaths`). Rather than write a third copy of
the same ADT-walking logic, `ClientConventionsValidator`'s original
private `PathIndex` was extracted into a new, package-visible
`CanonicalFieldPaths` class in the `canonical` package, and
`ClientConventionsValidator` refactored to use it -- no behavior
change, confirmed by keeping every existing test for that class
unmodified. Still a deliberate second implementation of
`mapping.CanonicalPaths`' walk, not a shared dependency on it -- see
`CanonicalFieldPaths`'s own javadoc for why that boundary (`canonical`
never depends on `mapping`) is worth preserving.

**A real risk, named plainly rather than glossed over.** This
resolution is now genuinely high-stakes in a way the earlier,
backstop-style resolvers weren't: a column removed from the prompt is a
column the model never gets a chance to reconsider. If a client's
configured alias or a canonical model's synonym is simply wrong, this
resolver will confidently produce an incorrect mapping with no
opportunity for the LLM to catch it -- whereas the model, seeing the
raw column, might have gotten it right. Client-configured aliases are
already validated for structural correctness (Step LLM-3) but not for
whether the human who configured them was actually correct, which
isn't something software can verify. Ambiguity detection (two different
fields' candidates colliding) fails closed, deferring to the LLM rather
than guessing -- but a *confident, wrong* alias or synonym is a
different risk category ambiguity detection can't catch, and is worth
someone reviewing the two real `synonyms:` blocks with fresh eyes
before trusting this in anything beyond a benchmark.

**What could not be verified without a live model call, stated plainly.**
Whether Qwen 3B actually respects the "these columns are already
resolved, don't expect to see them" instruction -- rather than getting
confused by a canonical model description that mentions more fields
than the (now-shorter) source table shows -- is exactly the kind of
thing this whole phase has insisted on checking against real model
output rather than assuming, and genuinely cannot be checked without
one. `filterResolvedColumns` and `renderAlreadyResolvedNote` (the two
pieces of pure logic feeding into the prompt) are directly unit-tested,
made package-private specifically so they could be; the actual
model-interaction path itself -- does the LLM behave sensibly when
shown a truncated table -- has no test coverage in this codebase and
needs your next real benchmark run to know for certain, the same
honest limitation already stated for the try/catch fix earlier in this
phase.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/canonical/CanonicalFieldPaths.java` (new -- extracted shared field-path index)
- `backend/src/main/java/com/alai/agenticsheets/canonical/ClientConventionsValidator.java` (modified -- uses the extracted class, no behavior change)
- `backend/src/main/java/com/alai/agenticsheets/canonical/CanonicalModelRegistry.java` (modified -- validates `synonyms` keys at load time)
- `backend/src/main/java/com/alai/agenticsheets/mapping/FieldAliasResolver.java` (new -- the deterministic column-matching resolver)
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- pre-resolution, table filtering, prompt note, merge; two helpers made package-private for direct testability)
- `backend/src/test/java/com/alai/agenticsheets/mapping/FieldAliasResolverTest.java` (new -- 9 tests)
- `backend/src/test/java/com/alai/agenticsheets/mapping/AgentMappingProposalServiceTest.java` (new -- the first test file for this class, 8 tests covering the two testable helpers)
- `backend/src/test/java/com/alai/agenticsheets/canonical/CanonicalModelRegistryTest.java` (modified -- 2 new synonym-validation tests)

**Tests added** (19 new):
- `FieldAliasResolverTest`: resolves via a field's own name, a canonical
  synonym, and a configured client alias, independently; resolves a
  sum-type field's column without touching variant mechanics
  (`selectedVariant`/`variantValueMap` stay null); `client_id` is never
  a candidate even with a configured alias; an unmatched column is left
  alone; multiple resolvable columns all resolve independently; two
  different fields' candidates colliding after normalization resolves
  neither; variant-qualified (dotted) sub-field paths are never
  candidates at all.
- `AgentMappingProposalServiceTest`: `filterResolvedColumns` removes
  only the specified columns, handles multiple columns, preserves every
  other top-level key and every other field within retained columns
  unchanged, is a no-op when nothing is resolved, and correctly
  produces an empty array when every column is resolved;
  `renderAlreadyResolvedNote` is empty when nothing was resolved and
  correctly lists each resolved field with its source column otherwise.
- `CanonicalModelRegistryTest`: a synonym referencing an unknown field
  fails that model alone, not the whole registry (mirroring the
  existing feed/convention validation tests exactly); valid synonyms
  load successfully and are retrievable.

**Confirmed live.** `mvn test` run against the real repo after
overlaying these files: **226/226**, up from round 2's 207 by exactly
the 19 new tests, no other test count moved, no regressions -- and the
brace-count false alarm caught mid-round (confirmed a pre-existing
artifact, not a real syntax error, by diffing against the actual
already-pushed file) held up: nothing broke in
`CanonicalModelRegistryTest` either.

## External review, round 3: a severe bug the field-alias merge introduced, plus a policy correction

A third external review, against the live pushed `main` (HEAD
`369d85a`), found the field-alias work itself was sound but its
interaction with this phase's own earlier safety mechanisms was not --
plus one genuine, unilateral design decision worth correcting rather
than defending.

### The severe bug: malformed model output could be silently hidden by deterministic mappings

Traced by hand before agreeing, since "built a safety mechanism, then
broke it in the very next change" deserves real scrutiny. Confirmed
exactly as the review described: at the merge point in `propose()`,
`proposal.unmappedSourceColumns()` refers to whatever the *model's own
response* said -- on a failed or malformed model call, that's the
empty, synthesized fallback proposal's empty list, never reconciled
against what's actually still missing. A column the model was supposed
to handle but never got the chance to -- the review's own worked
example, `Valuation Px` -- vanished from **both** `fieldMappings` and
`unmappedSourceColumns` simultaneously, with no signal anything was
ever wrong.

**Fix: a new, deliberately separate invariant.**
`MappingProposalStructuralValidator.validateColumnCoverage(MappingProposal, Set<String>)`
checks that every *observed* column is accounted for -- mapped by some
entry's `sourceColumn`, or explicitly listed in
`unmappedSourceColumns`, never both, and `unmappedSourceColumns` never
lists something that wasn't actually observed. Deliberately a separate
method, not folded into the existing `validate()`'s per-entry loop:
this project's test suite almost universally tests one field mapping
at a time against the real JPMC column set, without expecting every
*other* column to be independently accounted for in the same
assertion -- folding this check in would have broken essentially every
existing test for a reason unrelated to what each one actually tests.
Wired into both `propose()` and `validateEdited()`.

### Related: an attempted fix for infrastructure failures, and a real compilation failure it caused

The original `catch (RuntimeException e)` around the model call caught
genuine provider/infrastructure failures the same way as "the model
responded but its output was unusable" -- a real, valid concern.
Attempted a fix based on search results describing Spring AI's
`org.springframework.ai.retry.TransientAiException`/`NonTransientAiException`
hierarchy -- **never confirmed against this project's actual `pom.xml`
dependency** (`spring-ai.version` `2.0.0`) before shipping, and the real
`mvn test` run caught it: that package doesn't exist in 2.0.0 at all.

**What actually happened, traced properly this time.** Spring AI 2.0
deleted its own hand-rolled provider-exception hierarchy and now
delegates directly to vendor SDKs (confirmed via Spring AI's own
upgrade notes, not guessed) -- for the OpenAI-compatible path this
project uses, that's `openai-java`. Found strong, scenario-matching
evidence for the real exception type: a GitHub issue
(`spring-projects/spring-ai#6036`) titled exactly "Error in Spring AI
2.0.0-M6 while using Docker Model runner" -- the same combination this
project actually uses -- showing a real stack trace with
`com.openai.errors.NotFoundException` (extending
`com.openai.errors.OpenAIServiceException`, itself under the base
`com.openai.errors.OpenAIException`).

**Why that still wasn't attempted in code.** Strong evidence is not the
same as a confirmed compile, and this file had already broken a real
build once this round on an unverified guess -- risking a *second*
wrong class name and a third broken build was worse than leaving this
specific narrowing as open, named follow-up work. Reverted to catching
plain `RuntimeException` (guaranteed to compile, since it introduces no
new class reference at all) for both infrastructure and conversion
failures alike -- imprecise, but not silently broken, and not another
guess sent back without a way to verify it first. The actual fix
(catching `com.openai.errors.OpenAIException` specifically, once
confirmed against a real compile) remains real, open work, documented
plainly in the code's own comment at the point it matters rather than
just here.

### The architecturally important optimization: skip the LLM entirely when nothing is left for it

`propose()` now checks whether every observed column was already
resolved deterministically; if so, the model is never called at all --
extending `MappingResolutionService`'s existing "decide whether a model
call is needed" philosophy one level further. Tested end-to-end via a
new `Harness` exposing the individual mocks, with `verifyNoInteractions()`
on the `ChatClient` mock as the actual proof, plus the inverse case
(partial coverage still calls the model) and a third test proving the
model-call `catch` block's actual current behavior -- any
`RuntimeException` fails clean via `MappingProposalValidationException`,
not an unhandled crash -- rather than the originally-planned but
reverted infrastructure-vs-conversion distinction.

### A policy correction: canonical synonyms are not deterministic after all

`canonical-models/SCHEMA.md` already documented synonyms as LLM-hint
metadata, predating this resolver -- treating them as deterministic was
a real, unilateral policy shift. Corrected: `FieldAliasResolver` no
longer consults `CanonicalModel.synonyms()` at all, only a field's own
name and configured client aliases. Traced by hand against the real
JPMC fixture: without synonyms, only 7 of 11 columns still resolve
deterministically, so the real baseline will not trigger the
skip-the-LLM optimization -- a more conservative result than an earlier
read suggested.

**A real mistake caught mid-round.** While verifying this round's own
brace balance, `AgentMappingProposalServiceTest.java` showed a genuine
imbalance -- a `str_replace` edit had dropped the test class's own
final closing brace. Found and fixed before packaging, not after.

**Confirmed live.** `mvn test` run against the real repo after
overlaying these files: **236/236**, up from 226 by exactly the 10 new
tests, no other test count moved -- including after the mid-round
compilation failure and correction (the `org.springframework.ai.retry`
package that doesn't exist in this project's real Spring AI 2.0.0
dependency, reverted to a plain `catch (RuntimeException e)`): the real
build compiled clean and every test passed on the first run after that
fix, confirming the correction was actually right, not just plausible.

## First real run of the field-alias work: a real merge bug found, plus an unconfirmed model-behavior finding

Two runs against real Qwen 2.5 3B output, both against the corrected
pipeline (compilation fix included). Both failed structural
validation -- but for a genuinely interesting and, in one case, real
and fixable reason.

### The bug: the model's own stale mention of an already-resolved column

Both runs independently produced the same shape of failure:
`column(s) [X] are both mapped by a fieldMapping and listed in
unmappedSourceColumns -- contradictory` -- `Custodian` in the baseline
run, `Description` in the unfamiliar-column run. Both are columns the
"already resolved" note (see `renderAlreadyResolvedNote`) names
explicitly in the prompt, by design, so the model understands why the
table it's shown has fewer columns than the canonical model describes.
In practice, the model appears to echo that column name back into its
own `unmappedSourceColumns` anyway, even though the column was never
actually present in the table it worked from.

The merge logic already applied a "deterministic knowledge wins, the
model's confused duplicate is dropped" policy to redundant
`FieldMapping` entries -- but never applied the same policy to the
model's own `unmappedSourceColumns` list. Fixed: `mergedUnmapped` now
filters out any column already deterministically resolved, exactly
symmetric with the existing `FieldMapping` deduplication. Confirmed by
hand-tracing a new test
(`modelStaleMentionOfADeterministicallyResolvedColumn_filteredNotContradictory`)
built directly from this real failure shape: a model response that
correctly maps the genuinely unresolved column but also stale-mentions
an already-resolved one now merges cleanly, with the stale mention
filtered rather than surfaced as a contradiction.

### An unconfirmed, plausible finding: "Holdings." path prefixing

The baseline run's other 11 problems were all the same shape:
`canonicalFieldPath 'Holdings.as_of_date' is not a field in Holdings`
-- every single field path the model proposed was prefixed with the
model's own name. A plausible, but **not confirmed**, explanation:
`CanonicalModelPromptRenderer.render()` -- pre-existing code, untouched
by any change in this whole phase -- opens every prompt with
`"Canonical model: Holdings (version 1)"` immediately above the field
listing. It's entirely plausible the model inferred `Holdings.` as an
implicit namespace prefix, a common enough convention elsewhere. But
this is genuinely a hypothesis, not a diagnosis: this exact renderer
code has been unchanged and in use since Step 6, and this specific
confusion pattern has never shown up in any prior run this whole
phase -- which raises a real, unresolved question about whether
something about the *shorter* table (fewer columns than the canonical
model describes, a direct consequence of Step LLM-4's work) makes this
particular confusion more likely, or whether this is simply
run-to-run model variance under CPU inference that could recur or
vanish independent of anything in this codebase.

**Deliberately not "fixed" blind.** Guessing at a prompt-wording change
to prevent this, without seeing what the model's raw response actually
looked like, would repeat exactly the mistake this phase has tried
hard to avoid elsewhere. Raw response logging exists for precisely this
situation and defaults off (Step LLM-6's external review correction) --
this run didn't have it enabled. Recommended next step: re-run with
`AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE=true` to actually see the raw
text and confirm or rule out this hypothesis, rather than changing
prompt wording speculatively.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- `mergedUnmapped` now filters deterministically-resolved columns out of the model's own `unmappedSourceColumns`)
- `backend/src/test/java/com/alai/agenticsheets/mapping/AgentMappingProposalServiceTest.java` (modified -- 1 new test, built directly from the real failure)

**Tests added** (1 new):
- `modelStaleMentionOfADeterministicallyResolvedColumn_filteredNotContradictory`
  -- a model response correctly mapping the genuinely unresolved column
  while also stale-mentioning an already-resolved one; confirms the
  merged proposal excludes the stale mention entirely, both in
  `fieldMappings` (already worked) and now `unmappedSourceColumns`
  (the actual fix).

**Confirmed live.** `mvn test` run against the real repo, after
overlaying every round through the compose fix and both prompt
clarifications: **238/238**, up from 236 by exactly the 2 new tests
this section and the next each added, no regressions across five real
benchmark rounds' worth of changes landing together.

## Second real run: the merge fix confirmed working, and a low-risk attempt at the prefix confusion

Two more real runs against Qwen 2.5 3B, this time with the merge fix
from the previous round actually in place.

**The merge fix confirmed working in practice, not just by unit test.**
The baseline run's `Custodian` "both mapped and unmapped -- contradictory"
problem is gone entirely -- the exact column the fix targeted, in the
exact scenario the fix was built from. Real-world confirmation, not
just the traced unit test passing.

**A different contradiction in the unfamiliar-column run, correctly
still flagged.** `Description` shows the same "both mapped and unmapped"
shape -- but `Description` was never deterministically resolved in the
first place (it was a canonical-synonym-only match, and synonyms are no
longer deterministic as of this round's own earlier correction). This
is the model contradicting *itself* within one response -- proposing a
`fieldMapping` using `Description` as `sourceColumn` while *also*
listing `Description` in its own `unmappedSourceColumns`, with no
deterministic resolution involved at all. `validateColumnCoverage`
catching this is correct, desired behavior, not a false positive the
merge fix should have also suppressed -- a genuine internal
contradiction is a real problem worth surfacing, unlike the stale-note
echo the merge fix targets.

**The `Holdings.` prefix reproduced identically a second time.** Same
11 fields, same `Holdings.<field>` prefix, in a completely independent
run (fresh volume, fresh model call). Two independent, identical
reproductions moves this from "possibly one-off noise" to "a real,
reproducible pattern under this model and this prompt" -- worth an
actual, if still unconfirmed, mitigation attempt rather than only
waiting for a raw-logged diagnostic run.

**A low-risk, explicitly-labeled mitigation, not a confirmed fix.**
`CanonicalModelPromptRenderer.render()` now explicitly instructs against
prefixing a path with the canonical model's own name, right where the
"Canonical model: X" header sits -- the exact text suspected, not
confirmed, of causing the confusion. Unlike the earlier `org.springframework.ai.retry`
mistake (a wrong Java class name that silently breaks compilation), a
wrong guess about prompt wording is low-risk and easily reverted -- it
either helps, does nothing, or (worth watching for) makes things worse,
and only a real run can tell which. Explicitly documented, in both the
class javadoc and this doc, as an attempt, not a diagnosis -- if the
next real run still shows `Holdings.` prefixing, that's real evidence
the hypothesis was wrong, not a reason to quietly drop the instruction
and pretend it wasn't tried.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/CanonicalModelPromptRenderer.java` (modified -- explicit instruction against model-name path prefixing)
- `backend/src/test/java/com/alai/agenticsheets/mapping/CanonicalModelPromptRendererTest.java` (modified -- 1 new test confirming the instruction text is present)

**Tests added** (1 new):
- `explicitlyInstructsAgainstPrefixingAPathWithTheModelName` -- confirms
  the new instruction text renders; cannot and does not claim to test
  whether it actually changes model behavior, which only a real run can
  show.

**Still recommended, now more valuable than before given two
identical reproductions:** re-run with
`AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE=true` to see whether this
mitigation actually changed the model's raw output, not just to
diagnose the original cause -- a before/after raw-text comparison would
be considerably more informative than either run alone.

**Confirmed live, along with everything above.** `mvn test`: **238/238**
-- this section's 1 new test, confirmed passing alongside the previous
section's, in the same real run.

## Third real run: a real infrastructure gap found, plus encouraging (not confirmed) evidence

A real gap in this phase's own earlier work, found by the raw-logging
recommendation itself failing to work: `.env` had
`AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE=true` set, but the real docker
compose logs showed zero raw-response lines. A `.env` file only affects
`docker compose`'s own variable substitution -- it does not
automatically become part of a container's environment unless a
compose file explicitly forwards it. `compose.yaml`'s `backend` service
lists every environment variable it passes through one by one; this one
was simply never added when the opt-in logging feature itself was
built. The Spring side (`@Value` binding, `application.yml`'s default)
was correct the whole time -- it just never had anything to read,
because the OS environment variable it was waiting for never reached
the container at all. Fixed with one line in `compose.yaml`, matching
the exact pattern the existing `AGENTIC_SHEETS_INBOX_ENABLED` entry
already uses.

**Encouraging, but explicitly not confirmed: the `Holdings.` prefix did
not recur.** Both runs' validation errors are visible even without raw
logging (the structural validator reports exactly the
`canonicalFieldPath` the model proposed), and neither run shows a
single `Holdings.`-prefixed path this time -- a real change from two
consecutive prior runs that both showed it for every field. Genuinely
suggestive that the explicit counter-instruction helped. Not proof:
absence of one specific error doesn't confirm the mechanism, only that
the symptom didn't recur this time, and the raw-logging gap above means
this is inferred from validation output, not seen directly. Worth
re-running now that raw logging will actually work.

**A new, different, correctly-caught confusion pattern.** Both runs
show the model attaching `selectedVariant`/`variantValueMap` to fields
that were never sum types at all -- `asset_class.FixedIncome.maturity_date`
(a leaf field *within* a variant, not the sum type's own root path),
and, more strikingly, ordinary primitive fields with no sum-type
relationship whatsoever (`client_id`, `account_id`, `security_id`,
`security_description`, `market_price`). This is existing validation
(`'X' sets a variant but is not a sum type field`), unchanged by any
recent work, correctly catching a genuinely new failure shape -- not a
gap this round needs to close. Worth noting as a distinct model
confusion pattern from the ones already documented (schema-echo,
`Holdings.` prefixing, stale unmapped mentions): apparently the more
"sum type" mechanics get discussed in a prompt, the more the model may
over-apply them to unrelated fields.

**A real, open question, not silently resolved either way: case
sensitivity.** Both runs also show `unmappedSourceColumns` listing a
lowercased column name (`description`, `custodian`) where the real
observed header is capitalized (`Description`, `Custodian`).
`validateColumnCoverage`'s exact-string matching correctly flags this
as "never actually observed" -- consistent with how `sourceColumn`
matching already works everywhere else in this validator, not a new
inconsistency. Deliberately not "fixed" to case-insensitive matching
here: that would be a real, broader design decision (would need to
apply consistently everywhere column matching happens, not just this
one spot) trading away a genuine, if inconvenient, signal about model
reliability for convenience -- worth your input, not something to
decide unilaterally in the middle of a benchmark run.

**Files changed:**
- `compose.yaml` (modified -- forwards `AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE` into the backend container, matching the existing `AGENTIC_SHEETS_INBOX_ENABLED` pattern)

**No test coverage for this fix** -- it's a Docker Compose configuration
gap, not Java code; nothing in this project's `mvn test` suite exercises
compose file content. The only real verification is a live run showing
raw response text actually appear in the logs this time.

**Recommended next step, now that this should actually work:** re-run
`./scripts/local-llm/run-llm6-benchmark.sh` with the compose fix
applied and `AGENTIC_SHEETS_LOG_RAW_MODEL_RESPONSE=true` still set --
this should finally show the model's actual raw output, confirming or
correcting every inference made from validation output alone across
this and the previous two rounds.

## Fourth real run: raw logging finally working, and what it actually shows

The compose fix worked -- both runs' raw model text appears in the
logs. This is the first time in this whole step that an inference from
validation output alone could actually be checked against ground truth.

### Confirmed, not just inferred: the `Holdings.` prefix fix worked

Zero occurrences of a `Holdings.`-prefixed path in either raw response
-- every `canonicalFieldPath` the model produced is a clean, bare path.
Two consecutive runs showed the prefix on every field before the fix;
two consecutive runs show it on none after. This is now a confirmed
result, not an inference from the absence of one specific validation
error message.

### A genuinely valuable discovery: deterministic resolution silently protecting fields it doesn't know it's protecting

The raw text for `asset_class` and `currency` shows the model put the
literal string `"variantValueMap"` into the `selectedVariant` JSON
field for *both* -- a real, systematic confusion (see below) -- yet
neither field appears anywhere in the validation errors. Why: `"Asset Class"`
and `"Currency"` both match their canonical field's own name exactly,
so `FieldAliasResolver` resolves them deterministically before the
model is ever asked, and the merge fix from two rounds ago silently
discards the model's own (garbage) response for those two fields,
replacing it with the clean deterministic entry before validation ever
sees it. This is the deterministic architecture actively working,
caught in the act with real evidence for the first time -- not a
coincidence, and not something any single piece of this phase's design
was built to specifically anticipate, but a direct, provable consequence
of layering deterministic resolution ahead of the model.

### A new, systematic confusion, addressed with a targeted prompt clarification

For every field the model *did* have to resolve itself in the
unfamiliar-column run (`client_id`, `account_id`, `security_id`,
`security_description`, `market_price` -- none of them sum types), it
put the literal string `"selectedVariant"` into the `selectedVariant`
JSON field. Not choosing a variant -- echoing the *field's own name*
back as if it were a *value*. Five of five non-deterministic fields in
one response; a systematic pattern, not noise.

**Fix attempted, same methodology and same honesty about its
confirmation status as the `Holdings.` prefix fix.** The system
prompt's `selectedVariant`/`variantValueMap` explanation now explicitly
states these describe JSON *field names*, not values to echo back, and
explicitly states they apply only to a field that genuinely is a sum
type -- never to an ordinary field, and never to a sum type's own
nested sub-fields (the baseline run's separate
`asset_class.FixedIncome.maturity_date`/`coupon_rate`/`credit_rating`
errors are exactly that last case: the model tried to set a variant on
leaf fields *within* FixedIncome, not on `asset_class` itself). Not
confirmed to work -- only a real re-run can show that, the same
caveat as every prompt-wording attempt this step has made.

**Deliberately not attempted in this same round: the "one column, many
fields" hallucination.** The baseline run separately mapped `"Price"`
to `market_price` *and* to three different `FixedIncome` sub-fields
simultaneously, as if one column could encode multiple, unrelated
pieces of information. A real, distinct confusion, but not addressed
with another prompt patch this round -- stacking an ever-growing set of
narrowly-reactive instructions onto the same prompt risks papering over
symptoms rather than keeping the prompt fundamentally clear, and this
one hasn't been observed with the same repeated, systematic strength
the other two fixes were built from. Recorded as an open observation,
not silently dropped.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- system prompt clarifies that `selectedVariant`/`variantValueMap` are field names, apply only to genuine sum types, and never to a sum type's own sub-fields)

**No new tests** -- confirmed no existing test asserts on the exact
system prompt text that changed; this is a prompt-wording attempt, not
a code-behavior change, and (matching the `Holdings.` prefix fix's own
precedent) only a real model run can confirm whether it actually
changes output, not a unit test.

**Confirmed live.** `mvn test`: **238/238** -- this round changed no
code path a test would exercise differently (prompt text only), and
the real run confirms nothing else moved either.

**Recommended next step:** re-run with raw logging still enabled. Two
outcomes worth distinguishing precisely, not just "did it pass": does
the model now correctly leave `selectedVariant`/`variantValueMap` null
for non-sum-type fields (confirming this fix), and does `asset_class`/`currency`
still show the model's own garbage safely discarded by deterministic
resolution regardless (confirming that protection is robust, not a
one-run fluke)?

## Fifth real run: the first fully successful run of this whole benchmark, and confirmation of two prior fixes

### Confirmed, not inferred: the `selectedVariant`/`variantValueMap` fix worked

Both raw responses this run are completely clean -- every non-sum-type
field has `selectedVariant: null`, every sum-type field
(`asset_class`, `currency`) correctly uses `variantValueMap` with a
properly-formed dict, and there is not one instance of the literal
string `"selectedVariant"` or `"variantValueMap"` echoed back as a
value anywhere in either response. Five for five non-deterministic
fields showed the confusion before the fix; zero for eleven show it
after, across two consecutive runs. Confirmed via raw text this time,
not inferred from validation output.

### The first fully successful run of Step LLM-6's actual benchmark question

The unfamiliar-column run returned `curl exit 0`, HTTP 200 -- the first
clean pass this whole benchmark has produced. Worth being precise about
what that success actually contains, not just that it happened:

- **The genuine ambiguity question, answered correctly.** `market_price`
  resolved to `sourceColumn: "Valuation Px"` -- the actual unfamiliar
  column this fixture exists to test, correctly identified by the 3B
  model on its own, with no deterministic help (`"Valuation Px"`
  matches nothing by field name, synonym, or configured alias).
- **A second fix confirmed working, live, in the same run.** The raw
  model text still stale-mentions `"Custodian"` (already
  deterministically resolved, never shown to the model) in its own
  `unmappedSourceColumns` -- but the actual HTTP response returned to
  the caller shows `"unmappedSourceColumns": []`, empty. The merge fix
  from two rounds ago correctly filtered that stale mention out in a
  real successful run, not just the unit test built to prove it could.
- **Every deterministically-resolved field arrived correctly**, including
  `currency`'s `selectedVariant: "USD"` -- filled in by
  `SumTypeMappingResolver` from the real observed row data, not the
  model (which correctly never saw that column at all).

Multiple fixes from across this whole review-response chain --
deterministic field-alias resolution, the merge's stale-mention
filtering, the `selectedVariant`/`variantValueMap` prompt clarification
-- working together, live, in one successful real run. The strongest
single result this benchmark has produced.

### A new, single-occurrence finding, deliberately not patched yet

The baseline run is down to exactly one problem:
`unmappedSourceColumns lists [security_description], which was never
actually observed in the source table`. The raw text shows the model
correctly mapped `"Description"` to `security_description` in
`fieldMappings` -- then separately, redundantly, also listed
`security_description` (the *canonical field name*, not a source
column at all) in `unmappedSourceColumns`. A new, distinct confusion
from anything seen in this step so far -- not a stale deterministic
mention (`Description` was never deterministically resolved), not the
`selectedVariant` echo, not a case-mismatch of a real column name.

**Deliberately not patched this round.** Every prompt fix attempted so
far in this step was built from genuine, repeated evidence -- the
`Holdings.` prefix on two consecutive full runs, the `selectedVariant`
echo on five of five fields in one response. This is one occurrence.
Patching the prompt reactively for every single new thing observed
risks exactly what was already flagged as a real risk two rounds ago:
stacking narrow, reactive instructions instead of keeping the prompt
fundamentally clear. Recorded here as a watch item -- if it recurs on a
future run, that's the point to actually fix it, not this one.

**Confirmed live.** `mvn test`: **238/238** -- this round changed no
code path a test would exercise differently, and the real run confirms
nothing else moved either.

## Sixth round: the two remaining single-occurrence findings, addressed on direct instruction

Both were explicitly left as watch items rather than fixed reactively
-- every prompt fix before this one came from genuine, repeated
evidence (two runs for the `Holdings.` prefix, five fields in one
response for the `selectedVariant` echo), and neither of these had
recurred. Addressed now on direct instruction to resolve the remaining
3B findings before moving to model comparisons, not because new
repeated evidence appeared.

**`unmappedSourceColumns` receiving a canonical field name instead of a
source column header.** The baseline run's one remaining problem: the
model correctly mapped `"Description"` to `security_description` in
`fieldMappings`, then separately listed `security_description` (the
*canonical path*, not a column at all) in `unmappedSourceColumns`. The
system prompt now explicitly states `unmappedSourceColumns` must
contain the exact source column header text, never a canonical field
name or path, and explicitly says a column already used in a
`fieldMappings` entry isn't unmapped and shouldn't be listed in both
places.

**One column proposed for multiple canonical fields simultaneously.**
The baseline run's separate finding: `"Price"` mapped to `market_price`
*and* to three different `FixedIncome` sub-fields
(`maturity_date`/`coupon_rate`/`credit_rating`) at once, as if one
column of numbers could encode several unrelated facts. The system
prompt now explicitly states each source column supplies at most one
canonical field, and that proposing multiple different fields from the
same column is only legitimate when they genuinely represent the same
underlying value -- named as rare, not a normal case.

**The case-sensitivity question, deliberately still not resolved --
correctly, not left incomplete.** Re-examined rather than silently
carried forward: `validateColumnCoverage`'s exact-string matching
flagging a lowercase `"description"` against the real `"Description"`
header isn't a defect to fix -- it's the validator correctly reporting
a genuine model inconsistency, the same "don't silently repair, always
surface" principle this whole phase has held to throughout. Nothing
changed here, deliberately -- this was never actually a problem with
the code, and loosening it to be lenient would trade away a real
signal about model reliability for convenience, a design tradeoff still
worth a real conversation rather than a unilateral change bundled into
an unrelated fix.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- two further system prompt clarifications, class javadoc updated with the full findings history from this whole benchmark round)

**No new tests** -- confirmed no existing test asserts on the exact
prompt text that changed; matching every other prompt-wording attempt
in this step, only a real model run can confirm whether either change
actually affects output.

**Not run in this environment.** Same 238 expected -- prompt text only,
no code path a test exercises differently.

**Recommended next step:** re-run with raw logging enabled once more,
watching specifically for whether `unmappedSourceColumns` now contains
only real column headers and whether any single source column still
gets proposed for more than one canonical field. If both are clean,
that's confirmation; if either recurs, that's real evidence the
wording didn't land, which is exactly the outcome worth knowing plainly
rather than assuming success.

## CI investigation: real logs, a real (and reassuring) finding, and an unrelated fix

Real CI logs requested and reviewed after three consecutive failing
runs. `frontend-checks` ruled out directly (run locally: lint and build
both clean). The real failure was in `e2e-browser`, specifically one
assertion in `review-approval.spec.ts`'s browser journey -- not
anywhere in this whole benchmark round's backend changes.

**The reassuring part.** `e2e-golden-path` -- the pure API-level test,
exercising the exact same real propose/approve/dispatch pipeline this
whole round's changes touched, via `pipeline-api.spec.ts` -- passed
cleanly in the same CI run. That test's own `calls.length === 1`
assertion was a real, specific concern raised earlier (deterministic
resolution meaning `mapping-memory.spec.ts`'s currency-field assumption
never gets exercised by a live model call anymore) -- concern traced by
hand at the time, reasoned to likely still hold since
`SumTypeMappingResolver` applies the same "uniform value ->
selectedVariant" logic against real data either way. This CI run is
direct, live confirmation that trace was right, not just plausible.

**The actual failure.** `review-approval.spec.ts`'s browser journey
timed out waiting for a "Proposal {id}" heading to appear after
navigating to the review screen -- a plain Playwright default timeout
(5s), not a functional assertion failure. Traced into
`ProposalDetailPage.tsx`: the heading only renders once `detail` is
populated from `getProposalDetail()`; until then the page shows
"Loading...". A concrete, telling asymmetry in the same test file: the
"Approved." success check further down already uses an explicit,
generous 20s timeout, for -- per that code's own precedent -- exactly
this kind of reason. The heading check right after navigation never
got the same treatment.

**Fixed by matching that existing precedent, not inventing a new
number.** `review-approval.spec.ts`'s heading assertion now also uses a
20s timeout. Verified with `npx tsc --noEmit` against the e2e project's
own `tsconfig.json` -- clean, no type errors. Framed honestly: this is
the most concrete, plausible explanation available from the evidence at
hand (a real asymmetry in an otherwise-consistent file, plus a passing
API-level test proving the underlying pipeline itself is sound), not a
certain fix for a confirmed root cause -- no live repro was possible in
this environment, and if the failure recurs even with the longer
timeout, that would be real evidence pointing at something else
entirely, worth taking seriously rather than assuming this was the fix.

**Files changed:**
- `e2e/tests/review-approval.spec.ts` (modified -- the "Proposal {id}" heading check now uses a 20s timeout, matching the existing "Approved." check's own precedent)

**No new tests** -- this is a timeout adjustment to an existing E2E
assertion, not new coverage; the existing test's own pass/fail is the
verification, and only a real CI run can confirm whether the flake is
actually gone.

**Not run in this environment.** `mvn test` unaffected (no backend
code touched); the e2e TypeScript change verified via `tsc --noEmit`
only, not a live Playwright run -- this environment has no Docker, so
the actual browser journey couldn't be reproduced or re-verified here.

## Correction: the timeout fix was wrong, and the real root cause

The timeout fix above did not work. Confirmed locally, not just in CI:
`run-browser-tests.sh` re-run with the 20s timeout in place still timed
out -- waiting the *entire* 20 seconds and still finding nothing. That
result alone should have been the tell: a genuine slow-load flake
resolves well within 20s against a local stack; waiting the full
duration and still failing means something is deterministically broken,
not slow. The timeout theory is retracted, plainly, rather than left
standing as if it were the fix.

**The real cause, found by reproducing directly in a browser rather
than continuing to chase Playwright's own artifacts** (which turned out
to be a dead end in this environment -- Playwright's `outputDir`
defaults to `e2e/test-results/`, shared across all three E2E scripts,
and gets cleared at the start of every new run; running
`run-inbox-tests.sh` after `run-browser-tests.sh` silently wiped the
failure's own artifacts before they could be inspected). Opening
`http://localhost:5173/proposals/1` directly showed a blank page with:

```
Uncaught TypeError: Cannot read properties of null (reading 'map')
    at SourceCell (FieldMappingTable.tsx:53:32)
```

`FieldMappingTable.tsx`'s `SourceCell` renders `mapping.transformations.map(...)`
with no null-check -- and `frontend/src/api/types.ts`'s `FieldMapping`
interface types `transformations` as `TransformationStep[]`, never
`| null`. A real, explicit, pre-existing API contract this round's own
backend code silently violated: both `FieldAliasResolver`'s
deterministic `FieldMapping` construction and
`ProposalValidationService`'s synthesized `client_id` entry passed
`null` for `transformations` instead of `List.of()`. `FieldAliasResolver`'s
instance is the one that actually reaches a reviewer's screen (every
deterministically-resolved field -- `Custodian`, `Currency`,
`Asset Class`, `Quantity`, `Unit Cost`, `Market Value`, `As Of Date` --
carries this same null); `ProposalValidationService`'s is used only
for internal row-validation lookups and never gets serialized back to
the UI, so it wasn't the actual cause of the crash, but was fixed
anyway for the same non-nullable contract, on the chance a future
caller does surface it.

**Why nothing else in this whole round of work caught this.**
`pipeline-api.spec.ts` and `mapping-memory.spec.ts` -- the only tests
that ran cleanly across five real benchmark rounds and every prior
`mvn test` confirmation -- inspect JSON response *content* only; they
never render anything. `review-approval.spec.ts` is the one test in
this entire project that actually renders `FieldMappingTable`, and it
found this on the very first real run after `FieldAliasResolver`
shipped. Consistent with (and a direct, concrete justification for) the
project's own E2E testing rationale documented in `e2e/README.md`:
proof a pipeline *works* is not the same claim as proof the *screen a
human actually looks at* tells the truth about it.

**Fixed at both sources**, not patched at the rendering layer -- the
frontend's non-nullable contract is correct and shouldn't be loosened
to tolerate a backend that doesn't honor it. `FieldAliasResolver` and
`ProposalValidationService` both now construct `transformations` as
`List.of()`.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/FieldAliasResolver.java` (modified -- deterministic `FieldMapping` entries now use `List.of()` for `transformations`, not `null`; this is the fix that actually mattered for the observed crash)
- `backend/src/main/java/com/alai/agenticsheets/mapping/ProposalValidationService.java` (modified -- same fix for consistency, though this instance never reached the UI)
- `backend/src/test/java/com/alai/agenticsheets/mapping/FieldAliasResolverTest.java` (modified -- 1 new regression test)

**Tests added** (1 new):
- `deterministicallyResolvedFieldsNeverHaveNullTransformations` -- a
  direct, fast regression guard for the actual bug, confirming
  `transformations()` is non-null and empty for a deterministically-resolved
  field. It shouldn't take a live browser crash to catch this again.

**Confirmed live, both ways.** `mvn test`: **239/239**. And the real
confirmation this specific bug actually needed: `run-browser-tests.sh`
re-run end to end, both browser journeys passing, the previously-failing
one now completing in 4.5s -- not just "didn't time out," genuinely
fast, since there's no longer a crashed React tree stuck rendering
nothing. Real, direct proof the fix addressed the actual cause, not a
symptom: the same test that caught this bug in the first place is the
one that now confirms it's gone.

## Seventh real run: one confirmed fix, one confirmed-still-broken, and a real infrastructure failure

Two more real runs, checking specifically on the two prompt fixes from
two rounds back.

**Confirmed working: `unmappedSourceColumns` naming.** Both runs'
`unmappedSourceColumns` contained only real column headers this time --
no canonical field name leaked in, across both runs. This fix held.

**Confirmed NOT fixed, and worse: the sub-field hallucination.** The
baseline run's raw response mapped `CUSIP` to `maturity_date`, then
separately mapped `Price` to *both* `maturity_date` and `unit_cost` --
three separate violations of "one column, one field" in a single
response, all centered on `FixedIncome`'s own sub-fields. This is now
the third round this exact family of confusion has shown up (Step
LLM-6's original schema-echo finding, an earlier round's `Price` ->
`market_price` + three `FixedIncome` fields, and now this) -- crossing
this step's own bar for "repeated evidence, not reactive" that every
earlier fix was built from. The wording tried two rounds ago
("each source column supplies at most one canonical field") was
evidently too generic to actually land.

**A much more targeted fix, built from what's actually recurring.**
Rather than repeat the same general wording, the system prompt now
calls out variant-specific sub-fields specifically -- any dotted path
below a sum type field itself (`asset_class.FixedIncome.maturity_date`
and siblings) -- and states plainly that an incomplete `FixedIncome`
record (missing `maturity_date`/`coupon_rate`/`credit_rating` because
the source file simply doesn't carry that detail) is the normal,
expected outcome, not a gap to fill by repurposing a nearby column.
Whether this specific, narrower wording succeeds where the general
version didn't is -- like every prompt attempt in this step -- only
confirmable by a real re-run, not assumed here.

**Also newly observed, not yet patched.** The same baseline response
used `canonicalFieldPath: "description"` (invalid -- the real field is
`security_description`) for one entry, and a bare `maturity_date`
(missing the required `asset_class.FixedIncome.` prefix) for another.
Both single-occurrence, both left as watch items rather than patched
reactively, matching this step's own established discipline.

**A real infrastructure failure, not a model-confusion one.** The
unfamiliar-column run threw an actual `OpenAIIoException` after a full
5-minute timeout -- `Raw model response text for propose(): null`, no
model output at all, a genuine request failure rather than confused
output. This is the second real, live occurrence of exactly the
exception shape an earlier round's research (and reverted fix attempt)
predicted -- the `getSimpleName()` logged here matches that prediction
exactly. Worth flagging as likely resource pressure in the local
serving stack itself (Docker Model Runner under CPU inference), not
something addressable in application code.

**A safe, diagnostic-only improvement built from this new evidence --
deliberately not the full behavior-changing fix.** The earlier attempt
to distinguish infrastructure failures from conversion failures broke
a real build on an unverified package guess and had to be reverted.
This round adds a version with zero compilation risk: checking
`e.getClass().getSimpleName()` by substring (`"IoException"`,
`"IOException"`, `"TimeoutException"`, `"ConnectException"`) needs no
import at all, so a wrong guess about the exact class can only ever
produce a less-useful log message, never break the build. Control flow
is deliberately unchanged -- still a clean, reported validation
failure either way, not a re-thrown exception -- so a human reading the
logs during a real incident can now tell "the model was probably
unavailable" from "the model responded but said something unusable" at
a glance, without that diagnostic improvement risking anything else.
The actual behavior-changing fix (propagating an infrastructure
failure as something other than a 422) remains real, open follow-up
work, now with a confirmed real exception shape to build against
rather than a guess -- deliberately kept separate from this round's
diagnostic-only change, so a mistake in one wouldn't obscure whether
the other was right.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- targeted sub-field-hallucination instruction; diagnostic-only infrastructure-failure log distinction)
- `backend/src/test/java/com/alai/agenticsheets/mapping/AgentMappingProposalServiceTest.java` (modified -- 1 new test)

**Tests added** (1 new):
- `infrastructureShapedExceptionStillFailsCleanNotDifferently` -- a
  small test-only exception class named to match the real one's simple
  name, proving the new diagnostic branch didn't accidentally change
  control flow: still a clean `MappingProposalValidationException`,
  not a re-thrown exception or a different outcome.

**Confirmed live.** `mvn test`: **240/240**. The prompt-wording change
still needs a real benchmark re-run to know whether the more targeted
instruction actually lands where the general one didn't -- that's a
model-behavior question no test suite can answer, only a live run.

## Eighth round: closing out the two remaining watch items proactively

Both single-occurrence findings from the seventh round -- `"description"`
instead of `"security_description"`, and a bare `maturity_date` missing
its `asset_class.FixedIncome.` prefix -- addressed now on direct
instruction, rather than held back pending a second occurrence each,
same reasoning as the sixth round's proactive fixes.

**A single, unifying instruction rather than two narrow patches.** Both
findings share the same underlying shape: the model reconstructing or
paraphrasing a field path from memory instead of copying the exact
string the schema already gives it -- one direction (Step LLM-6's
`Holdings.currency`) added something extra, this direction removes
something real. `CanonicalModelPromptRenderer`'s existing "use the path
EXACTLY as shown" instruction was worded entirely around the
add-something-extra case; it now explicitly names shortening as an
equally wrong failure mode, using the two real, observed examples
directly (`security_description` -> `description`,
`asset_class.FixedIncome.maturity_date` -> `maturity_date`) rather than
a generic warning -- concrete enough to actually pattern-match against,
following the same lesson the sixth round's `llmsim`-adjacent findings
already suggested: specific, named examples land better than abstract
rules.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/CanonicalModelPromptRenderer.java` (modified -- explicit instruction against shortening a path, with the two real examples named directly; class javadoc updated)
- `backend/src/test/java/com/alai/agenticsheets/mapping/CanonicalModelPromptRendererTest.java` (modified -- 1 new test)

**Tests added** (1 new):
- `explicitlyInstructsAgainstShorteningAPath` -- confirms the new
  instruction text, including the two named examples, actually
  renders. Cannot and does not claim to test whether it changes real
  model behavior, matching every other prompt-wording test in this
  step.

**Confirmed live.** `mvn test`: **241/241**.

**Where this leaves the 3B findings, going into the next benchmark
run.** Every currently-known issue now has an attempted fix in place:
the `Holdings.` prefix (confirmed working), the `selectedVariant`/
`variantValueMap` echo (confirmed working), stale deterministic-column
mentions in `unmappedSourceColumns` (confirmed working, both at the
merge-logic level and via a real successful run), canonical names
leaking into `unmappedSourceColumns` (confirmed working), the
`FixedIncome` sub-field hallucination (fix attempted, unconfirmed),
and now path-shortening (fix attempted, unconfirmed). The next real
benchmark run is genuinely the right next step -- both to confirm the
two still-open attempts, and because a model this actively confused
about path fidelity will likely keep surfacing new patterns even once
these are resolved, the same way nearly every prior round has.

## Ninth real run: two fixes confirmed, one real interaction effect closed, two new omission-shaped findings

The richest single run this step has produced -- a genuine mix of
confirmed wins, one fix's real side effect, and two new findings that
don't fit the pattern of anything fixed so far.

**Confirmed working, cleanly, both runs: path-shortening.**
`security_description` spelled correctly in full across every mention
in both raw responses. The eighth round's fix landed.

**Confirmed working, mostly: the `FixedIncome` sub-field
hallucination.** Neither run's raw response contains a single
fabricated `fieldMappings` entry for `maturity_date`, `coupon_rate`, or
`credit_rating` -- the exact pattern that had recurred three times
running. The targeted, example-driven instruction from the seventh
round worked, in the specific sense it was built for: no more invalid,
wrongly-typed mapping entries repurposing an unrelated column.

**A real, unintended side effect of that same fix, closed by tying two
instructions together.** The model didn't just drop the missing
sub-fields cleanly -- it started listing their bare canonical names
(`maturity_date`, `coupon_rate`, `credit_rating`) in
`unmappedSourceColumns` instead, even though none of them were ever
real source columns. This is exactly the failure shape the
canonical-name-leak fix from two rounds earlier was built to prevent,
just not written with this specific new interaction in mind -- the
sub-field instruction told the model what NOT to put in
`fieldMappings`, without also reminding it that a field it's declining
still doesn't belong in `unmappedSourceColumns` either. Closed by
adding that connection explicitly, right where the sub-field guidance
lives, rather than assuming the two already-fixed instructions would
compose correctly on their own.

**Two genuinely new findings -- omissions, not wrong answers, a
different shape from everything fixed so far.** The baseline run
silently dropped `Price` -> `market_price` entirely, a completely
standard mapping this model has gotten right in nearly every prior
run across this whole step. The unfamiliar-column run silently ignored
`Valuation Px` -- the fixture's own genuinely unfamiliar column, the
actual question this whole benchmark exists to test -- appearing in
neither `fieldMappings` nor `unmappedSourceColumns` at all, correctly
caught by `validateColumnCoverage`'s "silently unaccounted for" check.
Both single-occurrence. Deliberately not patched: every prompt fix in
this step so far addressed the model saying something *wrong*; this is
the model saying nothing about a column at all, and there isn't yet a
clear, evidence-backed theory of what instruction would actually
address an omission rather than an incorrect answer. Recorded as open
watch items, same discipline as every other single-occurrence finding
in this step.

**Files changed:**
- `backend/src/main/java/com/alai/agenticsheets/mapping/AgentMappingProposalService.java` (modified -- explicit instruction tying the sub-field guidance to the existing unmapped-columns rule; class javadoc updated with the full ninth-round account)

**No new tests** -- confirmed no existing test asserts on the exact
prompt text that changed; matching every prompt-wording change in this
step, only a real model run can confirm whether it actually changes
output, not a unit test.

**Confirmed live.** `mvn test`: **241/241**, unchanged as expected --
prompt text only, no code path a test exercises differently.

**Where this leaves the 3B findings.** Confirmed working: the
`Holdings.` prefix, the `selectedVariant`/`variantValueMap` echo, both
`unmappedSourceColumns` naming fixes (stale-mention and canonical-name),
path-shortening, and (with this round's follow-up) the `FixedIncome`
sub-field hallucination. Open, unconfirmed: this round's connecting fix
for the sub-field/unmapped-columns interaction. Open, unaddressed by
design: the two new omission findings, pending either recurrence or a
real theory of what would help. Worth treating the next run as a real
test of whether this step's fixes have actually converged, or whether
omissions turn out to be the next recurring pattern the way each prior
category was.
