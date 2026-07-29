# Mapping notes for the sample data

These are the mappings I *think* are correct for each sample file, written
out the way a human reviewer would see them in the eventual approval UI.
Check these against your actual understanding before anything gets built
on top of them — that's the point of this exercise.

## holdings_jpmc_20260115.xlsx → holdings

Single header row, no banner, per-row date column. Client id (`jpmc`) comes
from the filename, not from any column.

| Source column | Canonical field       | Notes |
|---|---|---|
| Account        | account_id            | direct |
| As Of Date     | as_of_date            | direct, already a real date |
| CUSIP          | security_id           | direct |
| Description    | security_description  | direct |
| Asset Class    | asset_class           | direct |
| Quantity       | quantity              | direct |
| Unit Cost      | unit_cost             | direct |
| Price          | market_price          | direct |
| Market Value   | market_value          | direct |
| Currency       | currency              | direct |
| Custodian      | custodian             | direct |

Confidence: high on every field — column names are close enough to the
canonical names that this is close to a literal rename.

## holdings_metlife_20260201.xlsx → holdings

Row 1 is a merged banner (`MetLife Holdings Extract — Report Date:
02/01/2026`), real header on row 2. `as_of_date` is **not a per-row
column** — it's parsed out of the banner text and applied as a constant
to every row in the sheet. Client id (`metlife`) again comes from the
filename.

| Source column  | Canonical field       | Notes |
|---|---|---|
| *(banner text)* | as_of_date            | parsed from "Report Date: 02/01/2026" in the row-1 banner using `client-configs/metlife.yaml`'s `dateFormat: MM/dd/yyyy`, applied to every row — not a column mapping |
| Portfolio       | account_id            | direct, different name |
| Sec ID          | security_id           | direct, different name |
| Sec Name        | security_description  | direct, different name |
| Class           | asset_class           | direct, different name |
| Units           | quantity              | direct, different name |
| Cost/Unit       | unit_cost             | direct, different name |
| Mkt Price       | market_price          | direct, different name |
| Mkt Val         | market_value          | direct, different name |
| Ccy             | currency              | direct, different name |
| *(none)*        | custodian              | not provided by this client — canonical value is NULL, not an error |
| Trader Notes    | *(unmapped)*           | no canonical home; should be surfaced to the reviewer as "ignored", not silently dropped without being shown |

Confidence: high on the renamed columns (still a 1:1 concept match). The
missing-custodian case is expected, not an error. The banner-date
extraction is a different kind of uncertainty than the date-format
question (which `client-configs/metlife.yaml` now resolves) — it's about
correctly finding "02/01/2026" inside free-text banner content at all,
which still deserves a flagged/lower confidence score prompting a human
to actually look, not just rubber-stamp.

## rate_reset_pimco_20250501.xlsx → market_rate_book_value

Banner row present but carries no data this time (just a title) — contrast
with the MetLife file, where the banner *was* the only source of a field.
Both date columns are stored as **text strings in MM/DD/YYYY format**, not
native Excel dates — needs an explicit parse, not a type coercion.

| Source column   | Canonical field | Notes |
|---|---|---|
| Instrument       | instrument_id   | direct |
| Desc             | description     | direct |
| Reset Rate (%)   | market_rate     | **RESOLVED, changed**: canonical stores a fraction, not a percentage — value ÷ 100 (5.375 → 0.05375), not a direct pass-through |
| Book Val ($)     | book_value      | direct |
| Effective Date   | effective_date  | text "05/01/2025", parsed per `client-configs/pimco.yaml`'s `dateFormat: MM/dd/yyyy` → 2025-05-01 |
| Report Date      | as_of_date      | same parse, same client config |
| Currency         | currency        | direct |

Confidence: high across the board now — the date parsing used to be the
one field I'd have flagged for lower confidence (MM/DD vs DD/MM is
genuinely ambiguous from a single file when day ≤ 12), but with
`client-configs/pimco.yaml` declaring the format explicitly, there's
nothing left to guess.

## Open questions this exercise surfaced

1. ~~Is `market_rate` really a percentage number, or a fraction?~~
   **RESOLVED: fraction** (0.05375, not 5.375). See the corrected PIMCO
   mapping table above and `market_rate_book_value_expected.csv`.
2. ~~Should an unmapped source column (`Trader Notes`) block approval,
   or just get flagged as ignored?~~ **RESOLVED: ignored, not blocking.**
   A column with no associated canonical field is simply not part of the
   mapping — no error, no approval gate. (Still likely worth *listing* as
   "ignored" in the review UI for transparency, so a reviewer can see
   nothing was silently missed — but that's a UI nicety, not a rule the
   mapping logic itself needs to enforce.)
3. ~~Is `client_id` always safe to derive purely from the filename?~~
   **RESOLVED: yes** — confirmed as the convention actually followed.
4. ~~Ambiguous dates: MM/DD/YYYY vs DD/MM/YYYY can't be resolved from a
   single file when day ≤ 12.~~ **RESOLVED, in two parts:**
   - Canonical (output) date format is now declarable directly on the
     ADT's `Date` primitive — `{ type: Date, format: yyyy-MM-dd }` — see
     `holdings.yaml`'s `as_of_date` field and the new "Type kinds"
     section of `SCHEMA.md`.
   - Input format is a *client* property, not a canonical-model property
     (JPMC and MetLife both feed `Holdings` but don't share a date
     convention) — see the new `client-configs/*.yaml` files and
     `SCHEMA.md`'s "Source conventions" section. `jpmc.yaml`,
     `metlife.yaml`, and `pimco.yaml` each declare their own
     `dateFormat`, so parsing never has to guess per file.

## ADT reframing (this round)

The canonical model configs now live in `canonical-models/*.yaml` using the
DSL in `canonical-models/SCHEMA.md` — product types (records) and sum types
(tagged variants), not a flat field list. Questions 1–4 above are all now
resolved. One effect worth noting:

- **`asset_class` moved from a free-text `String` to a sum type**
  (`AssetClass`, with `FixedIncome` carrying its own maturity/coupon/rating
  fields Equity doesn't have). This is a real behavior change, not just
  cleaner typing — it means the mapping agent now has to decide *which
  variant* a row belongs to, not just extract a string.

## Resolved: market_rate_book_value is a product type, not a sum

At Prudential, both fields were typically available on input together.
`market_rate_book_value.yaml` no longer has a `RateOrBookValue` sum type —
`Entry` just has `market_rate: Number` and `book_value: Number` directly,
both present on every row.

## Resolved: secrets for this iteration

`secretRef` resolves to a plain OS environment variable, sourced from a
`.env` file via `docker-compose`'s `env_file` — same pattern
`sheets-reader-mcp` already uses. No external secret manager for now.

## Lesson from Prudential, folded into the design

Canonical configuration there was "not constant at all," and the system
that resulted treated it as a raw JSON string, re-parsed in multiple loops
and segments scattered through the codebase. That's the specific failure
this project's `CanonicalModelRegistry` design (see the new "Loading &
reload" section in `SCHEMA.md`) exists to avoid: parse each config exactly
once into a typed, validated object; every consumer reads that object,
never raw text; reload is atomic and fail-safe so a bad edit to one team's
file can't corrupt state or take down another team's pipeline; and
`version` is load-bearing (pinned on in-flight proposals, keyed into the
mapping-memory cache) so a config change doesn't retroactively invalidate
work in progress.

## Resolved: how a sum type becomes a stable column

Moot, as it turns out — the system never touches a database at all.
Persistence is entirely the receiving team's responsibility, on their own
service, in their own schema (see `target:` in both configs and the new
"Target service" section of `SCHEMA.md`). Whatever representation
strategy I'd sketched here (wide-nullable / JSONB / subtype tables) simply
isn't this system's decision to make.

`agentic-sheets` still needs its *own* Postgres, but only for orchestration
bookkeeping — `import_batch`, `mapping_proposal`, `mapping_memory`, and a
delivery log — not for canonical data. That's a real distinction: a
system of orchestration, not a system of record.

## New open questions from the delivery model

1. ~~Where do secrets referenced by `secretRef` actually come from?~~
   **RESOLVED for this iteration: `.env` files** (see above).
2. ~~What counts as a successful delivery? Does the team's service get
   to reject a structurally-valid payload for its own business reasons,
   and if so is that retried or terminal?~~ **RESOLVED: configurable
   per team**, via the new `target.delivery` block in `SCHEMA.md`
   (`retryableStatusCodes` / `terminalStatusCodes`, defaulting to
   "retry 5xx and network failures, treat 4xx as terminal"). Delivery
   semantics are a user's choice, same as `transport` — not something
   the system should hardcode once for every team.
3. ~~Retry policy specifics — attempts, backoff, when to surface a stuck
   delivery to a human.~~ **RESOLVED, same block**: `maxAttempts`,
   `backoff`, `initialDelaySeconds`, `maxDelaySeconds`, all with defaults,
   all per-team overridable. `holdings.yaml` shows an explicit override;
   `market_rate_book_value.yaml` omits the block entirely to show the
   all-defaults case. A delivery that exhausts `maxAttempts` still needs
   a "failed to deliver" status surfaced somewhere a human sees it —
   likely the review UI, gaining a fourth status alongside pending/
   approved/rejected — but that's UI work for Step 8, not a config
   question.

## Step 6 build notes

The mapping agent is live and confirmed working against the real JPMC
fixture — structured output binding, `CanonicalModelPromptRenderer`'s
ADT-to-text walk (including the `AssetClass` sum type), and the
`synonyms:` block added to the config format specifically to serve this
step all check out end to end. Two things surfaced during live testing
that weren't obvious from the design alone, both fixed and worth keeping
as a record of why the code looks the way it does:

**A sum type's variant needs two resolution modes, not one.** The first
version of `MappingProposal.FieldMapping` had a single `selectedVariant`
field. Against the real JPMC file, the agent correctly recognized that
`asset_class` varies row by row (`Equity` for some rows, `Fixed Income`
for others) — but there was nowhere in the output schema to express
that, so `selectedVariant` came back empty with the reasoning explained
only in free-text `conversionNotes`. That's a real structural gap in the
proposal shape, not a prompting problem: a single per-column variant
choice only makes sense when a whole file is one fixed variant. Fixed by
adding `variantValueMap` (raw source value → variant name) as the
second mode, with the system prompt explicit about when to use which.
Confirmed live afterward: `currency` (every row is `USD` in this file)
correctly used `selectedVariant`, while `asset_class` (genuinely mixed)
correctly used `variantValueMap` — the model chose the right mode on its
own once the schema actually had room for the distinction, it wasn't
guessing.

**`client_id` needed to be explicitly ruled out, not just left
unprompted.** Even though `client_id` is always derived from the
filename (confirmed convention, see the "checked in the javadoc
updates" round) and is passed into `propose()` as a known parameter, the
agent still proposed a mapping for it on the first live run —
`sourceConstant: "jpmc"`, confidence 0.6, "inferred from the file
source." Not wrong, exactly, but the system already knows this with
certainty, and letting the agent re-derive a fact it's already been
handed wastes a mapping slot and gives a falsely-low confidence for
something that isn't actually uncertain. Fixed by telling the system
prompt explicitly that the client is already resolved and any
`client_id`-named field should be excluded from `fieldMappings`
entirely — confirmed live: it disappeared from the output completely,
not just given a mapping and ignored downstream.

Both of these are the same underlying lesson: when something surprising
shows up in a live LLM response, check whether the *output schema* gives
the model room to say the right thing before assuming it's a prompting
problem. The first fix was structural (a missing field), the second was
informational (a missing fact) — worth telling apart, since they look
similar in the response text but need different fixes.

`holdings_metlife_20260201.xlsx` — which exercises `sourceConstant`
(banner-derived `as_of_date`), a genuinely missing `custodian`, and an
unmapped `Trader Notes` column, none of which JPMC's deliberately-easy
file tests — was tried live after this round of hardening. See the
"MetLife verification" section below for the results; short version:
the missing-field and unmapped-column cases came back exactly right,
and the banner-content gap this document already predicted was
confirmed for real rather than just reasoned about.

## Step 6.1 hardening (external review)

An external review (ChatGPT, reviewing the checked-in repo statically)
went through Step 6 in real depth and caught several things worth
crediting directly rather than folding in quietly. Two full review
rounds — the second specifically checking whether the first round's
documentation fixes actually landed, and correctly finding they hadn't
gone far enough (the README still contradicted itself in a different
place). That second-pass rigor is exactly the kind of review that's
actually useful. What follows is what got fixed now, and — just as
important — what didn't, with the reasoning either way.

### Fixed

**The structured-output/ADT-binding claim was genuinely wrong, not just
imprecisely worded.** The design docs said the agent "is bound to" the
canonical ADT via structured output. What actually happens: Spring AI
binds the response to the fixed `MappingProposal` Java record; the ADT
is rendered as prompt *text* by `CanonicalModelPromptRenderer`, nothing
more. Fixed two ways: (1) corrected the claim everywhere it appeared —
README's design principles, the Step 6 roadmap line, `SCHEMA.md`'s "why
this matters" section — to describe what's actually true now versus
what Step 7 will make true; (2) added
`MappingProposalStructuralValidator`, run immediately after decoding and
before persistence, which checks a proposal's `canonicalFieldPath`
values are real paths in the resolved ADT, `sourceColumn` values were
actually observed columns, `selectedVariant`/`variantValueMap` are
mutually exclusive and reference real variant names, confidence is in
`[0.0, 1.0]`, and there are no duplicate field paths. A
`MappingProposalValidationException` on failure means a structurally
broken proposal is rejected (422) rather than silently persisted.

**The config-reload race was a real bug, not a hypothetical one.** The
controller resolved a `CanonicalModel` to create the `import_batch`,
then called `MappingProposalService` with just the model ID — which
independently re-resolved it from the registry inside the service. Since
the registry reloads on a schedule, a reload landing between those two
calls could make the prompt actually shown to the agent disagree with
the `config_version` recorded alongside the resulting proposal. Fixed by
resolving `CanonicalModel` and `ClientConfig` exactly once in the
controller and threading both through as parameters — no second
registry lookup anywhere in the request.

**`import_batch`'s identity was missing `worksheet`, and the dedupe
logic had a real race.** The original unique key was just
`(source_filename, content_hash)` — two different worksheets in the same
workbook, or the same file resubmitted for a different model/client,
would collide onto one batch, silently misattributing a proposal to the
wrong model or client in its recorded metadata. Fixed by adding a
`worksheet` column and widening the identity to
`(source_filename, content_hash, worksheet, model_id, client_id,
config_version)`. Separately, the original `findOrCreate` was a
select-then-insert, which lets two concurrent requests race each other
with one failing on the constraint instead of both cleanly resolving to
the same row — replaced with a single atomic
`INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING id`.

**`FileHasher` read the whole workbook into memory and only guarded
against literal `../` in the path string.** Fixed to hash via a
buffered `DigestInputStream` instead of `readAllBytes`, and to resolve
both the workspace root and the target file through `toRealPath()`
before comparing — that's what actually closes off a symlink pointing
outside the mounted root, which string-level `normalize()` alone
doesn't catch.

**The prompt didn't say the spreadsheet content was untrusted.** The
`describe_table` result (including sample cell values) was concatenated
directly into the user prompt with no framing. A malicious or just
weird cell value containing instruction-like text had no explicit
signal telling the model to treat it as data, not guidance. Fixed by
delimiting the source table clearly and stating explicitly, in the
system prompt, that everything inside those delimiters is untrusted
data to be mapped, never instructions to follow.

### Explicitly deferred, with reasoning

**Full per-model dynamic JSON-schema generation**, so the agent's
structured-output call itself is constrained by field-path enums,
`oneOf` on `sourceColumn`/`sourceConstant`, variant-name enums, and so
on — not just checked after the fact. This is a real, valuable
enhancement, and the review's suggested shape for it
(`MappingPlanSchemaFactory`) is sound. It's also a genuinely large
feature — a schema-generation subsystem, not a quick fix — and it would
substantially duplicate logic Step 7's deterministic validator already
needs to build anyway (both need "does this reference a real path,
does this variant name actually exist" logic against the same ADT).
The scoped alternative landed instead — a lightweight post-decode
structural check — closes most of the practical risk (a human reviewer
still sees every proposal before anything is approved) without
building that subsystem twice. Worth revisiting if Step 7's validator
ends up needing schema-generation machinery anyway, at which point
Step 6 could reuse it rather than duplicate it.

**Giving the agent access to banner/pre-header content.** The review
correctly predicted a real gap: `describe_table` doesn't expose the
rows above the detected header, so the agent is told to use
`sourceConstant` for banner-derived values (like MetLife's as-of-date)
without actually being able to see what's in the banner. This needs a
change to `sheets-reader-mcp` itself — a `preambleRows` field on
`TableDescription`, or a dedicated tool — which is a different repo and
a real design decision (how many rows, what if the "banner" isn't
uniformly structured), not something to bolt on here. Deferred until
the MetLife fixture is actually tried, since that's when this gap
becomes concrete rather than theoretical.

**An llmsim-style deterministic mock test harness and Testcontainers-based
Postgres integration tests**, matching how `sheets-reader-mcp` tests its
own MCP surface without depending on live, nondeterministic model
responses in CI. This is the right long-term answer to "Step 6 lacks
tests at its actual boundary" — `MappingProposalService`,
`MappingController`, batch dedup, and reload-during-inference are all
genuinely untested right now beyond live manual curl checks. Building
that harness is real infrastructure work (a scripted fake model
response flow, a Testcontainers-backed Postgres for repository tests),
not a quick addition. `MappingProposalStructuralValidator` at least got
proper unit tests now, since that logic is pure and testable without
either piece of infrastructure.

**Full inference provenance** (LLM provider/model ID, prompt-template
version, schema version, config hashes, MCP table-description hash,
token usage, latency, failure category). Valuable for debugging and
reproducibility, genuinely more schema and capture logic than fits in
this pass. `config_version` already gives partial provenance; the rest
is real future work, not done here.

**Explicit-by-name MCP client selection** instead of
`SpreadsheetExplorerService` taking "the first configured client." Still
correct as flagged, still only matters once a second MCP server exists
— no behavior change needed while there's exactly one.

**Deciding whether repeated inference should be idempotent (one proposal
per batch) or produce multiple numbered attempts.** This is a real
product/UX question that affects how Step 8's review UI presents
proposals to a human, not just how they're stored — better decided
alongside that UI than forced now.

## MetLife verification

Tried the fixture flagged as untested throughout Step 6/6.1 —
`holdings_metlife_20260201.xlsx`, the one built specifically to exercise
`sourceConstant`, a genuinely missing field, and an unmapped column, none
of which JPMC's easy file tests. Results:

**Confirmed the predicted gap, not just theorized it.** `describe_table`'s
response has `headerRowIndex: 1` and goes straight into `columns` — the
row-0 banner ("MetLife Holdings Extract — Report Date: 02/01/2026")
isn't present anywhere in what the agent sees. The external review's
prediction that `sourceConstant` couldn't be reliably resolved without
exposing pre-header content was exactly right, now confirmed live
rather than just reasoned about.

**The agent's actual response to that gap is worth understanding
precisely.** It didn't fail or leave `as_of_date` unmapped — it parsed
the date straight out of the *filename string* instead
(`holdings_metlife_20260201.xlsx` → `2026-02-01`), landing on the
correct value with confidence 0.6 and an honest note about where it
came from. That's good calibration (it correctly flagged this as less
certain than a direct column match), but the mechanism is fragile in a
way the correct value obscures: it only works because this fixture's
filename date happens to agree with the banner's date. A real client
could resubmit a corrected file under a new filename with the *same*
underlying as-of-date, or use a naming convention that doesn't encode a
date at all, and this fallback would silently produce a wrong or
missing answer with no signal that anything was off. Worth being clear
about when `sheets-reader-mcp` eventually exposes banner content: that's
not just a nice-to-have, it's removing a fallback that currently only
looks correct by fixture coincidence.

**`custodian` and `Trader Notes` both came back exactly right** —
`custodian` at confidence 0 with "No custodian/custodian bank column
present in this sheet," `Trader Notes` correctly landing in
`unmappedSourceColumns` rather than being forced into a mapping.

**New minor gap, not previously visible with JPMC's simpler file**:
`asset_class`'s `variantValueMap` included `Cash` and `Alternative`,
which never appeared in the 3 sampled rows (only `Equity` and
`Fixed Income` did). `MappingProposalStructuralValidator` doesn't catch
this — it checks that `variantValueMap` *values* are real variant
names (they are), not that the *keys* correspond to values actually
observed in the source data. Harmless in this case (extra unused
lookup entries), but worth tightening if it turns out to generalize
into fabricating variants that don't exist in a client's actual data
rather than just ones absent from a 3-row sample. Would need the
validator to see observed source cell *values*, not just column
headers — currently only has the latter.

**New behavior worth noting for Step 7**: for `asset_class.FixedIncome.
maturity_date` and `.coupon_rate`, with no dedicated source column
available, the agent attempted to parse them out of the free-text
`Sec Name` column instead (e.g. "FHLB Note 4.25% 2027" → coupon_rate
4.25, maturity year 2027), at appropriately low confidence (0.4-0.45)
with explicit caveats about incompleteness. Reasonable behavior for a
proposal a human reviews before anything happens — but it means the
*same* source column can legitimately feed multiple different
canonical fields via different extraction logic, not a strict
one-column-to-one-field mapping. Step 7's row-construction logic needs
to handle "parse this cell two different ways depending on which
canonical field is being derived from it," not just "copy this
column's value."

**The structural validator handled a genuinely more complex case
correctly** — this proposal has 13 field mappings including two
data-dependent variant resolutions, a banner-derived constant, and two
free-text-extraction attempts, and passed validation cleanly. First
live confirmation of the validator beyond its own unit tests.

## Step 7 build notes

**Real bug caught while writing tests, before it ever hit a live
run.** `CanonicalRowBuilder` treats `client_id` as a required ADT field
with no special case -- correctly so, since it doesn't know anything
about the mapping pipeline's own conventions. But `MappingProposalService`
explicitly tells the agent to never propose a mapping for `client_id`
(the Step 6 fix from a few rounds back). Put those two correct decisions
together unmodified and every single row would fail validation on a
field the proposal was deliberately told to omit. Fixed in
`ProposalValidationService`: inject a synthetic `client_id` mapping
(`sourceConstant` = the already-known `client.clientId()`) before
building any row. `ProposalValidationServiceTest` is a direct regression
test for this -- a proposal with no `client_id` entry, validated
successfully. Worth naming as a pattern: two independently-correct
pieces of code can still combine into a bug at the seam between them;
writing the test came before running against real data specifically
because unit tests forced spelling out "here's a real proposal shape"
in a way manual curl testing hadn't yet.

**Scope decisions, stated plainly:**

- **Batch-level dispatch, not row-level.** One HTTP call per batch, with
  the payload being a JSON array of every row that passed validation.
  Matches `delivery_log`'s existing shape (one row per delivery
  *attempt*, not per canonical row) and is simpler than N separate calls
  with N separate retry states. Rows that fail validation are excluded
  from the payload and reported back in `ValidationReport`, not silently
  dropped -- partial delivery of the rows that *are* valid seemed more
  useful than blocking everything on one bad row, but this is a real
  product choice, not an obviously-correct one.
- **REST + api-key only, for real.** `transport: mcp` would need
  creating an MCP client connection to an arbitrary team-specified
  endpoint at dispatch time -- a materially different feature from the
  static, compose-time-configured connection this project already has to
  `sheets-reader-mcp`, and one this project isn't going to guess at
  implementing given how many times guessing at an unfamiliar API shape
  has gone wrong this session. `oauth2-client-credentials` and `mtls`
  are real auth flows, also not guessed at. All three fail fast with an
  explicit "not yet implemented" error (recorded in `delivery_log` too,
  so it's not invisible) rather than pretending to try or silently
  sending something unauthenticated.
- **`java.net.http.HttpClient`, not a Spring HTTP abstraction.**
  Deliberate: needs no new dependency, and its API has been stable since
  JDK 11 -- the safest possible choice after this session's repeated
  experience of guessing wrong about framework API shapes that turned
  out to have changed between versions (Jackson 2→3, the MCP client's
  eager initialization, `@ComponentScan` not overriding
  `@SpringBootApplication`'s implicit scan). Core JDK API isn't immune
  to that risk, but it's about as low as this kind of risk gets.
- **Synchronous, blocking dispatch.** A retry loop with backoff blocks
  the HTTP thread handling the `/approve` request for however long
  retries take. Fine for a manually-triggered, single-operator
  prototype; a production version would want this running async off a
  queue instead of inline with the approval call. Not built now --
  stated here so it isn't quietly assumed to be fine at a larger scale.
- **A local `/internal/fake-target` endpoint**, purely for testing --
  `holdings.yaml`'s `target.endpoint` points at it so Step 7's dispatch
  path is actually exercisable without external infrastructure or a
  second running service. Clearly marked as testing-only in its own
  javadoc; a real team's target lives entirely outside this project, and
  this endpoint is not a model for how one should be built.
- **Fail closed on a config version mismatch.** `/approve` refuses to
  proceed if the canonical model has moved to a different version since
  the proposal was created (the exact race Step 6.1 fixed for the
  propose path -- this closes the same class of gap on the approve
  path). Rather than silently validating against whatever the registry
  currently holds, it returns a clear 409 telling the caller to re-run
  `/propose` against the current config. A real historical-version store
  (keeping old `CanonicalModel`s retrievable by version, not just the
  latest) would be the more complete fix; not built now, since it's a
  real feature (versioned config storage), not a quick addition.
- **Sum type values serialize as a discriminated object** --
  `{"type": "<VariantName>", ...fields}` -- since JSON has no native sum
  type. Documented in `SCHEMA.md`'s "Target service" section too, since
  it's part of the wire contract a receiving service needs to know, not
  just an implementation detail.
- **Date output doesn't yet honor a primitive's declared `format`.**
  `CanonicalValueJson` always serializes a `DateValue` via
  `LocalDate.toString()` (ISO-8601). Every current canonical model's
  Date fields use the default `yyyy-MM-dd`, which happens to coincide
  with ISO, so this hasn't mattered in practice yet -- but it's a real
  gap if a team ever configures a different output format.

### Live confirmation: the full pipeline works end to end

First real run of `propose` → `approve` → validate → construct →
serialize → dispatch, all the way through, against the JPMC fixture. All
four rows validated with zero errors: `client_id` injection worked
correctly (confirming the fix above), both `Equity` and `FixedIncome`
variants resolved correctly via `variantValueMap` (including
`FixedIncome`'s unmapped `maturity_date`/`coupon_rate`/`credit_rating`
correctly coming through as absent rather than errors, since JPMC's file
never had that data), `currency` resolved the same way, and
`custodian`'s string value carried through untouched. Dispatch reached
the local fake-target on the first attempt (200, no retries needed) and
`delivery_log` recorded it.

One easily-misread detail: numbers like `unit_cost: 280` and
`market_value: 926500` show up without decimal places even though the
source cells were `280.00` and `926500.00`. Not a bug -- `BigDecimal`
preserves whatever scale the raw text from `read_rows` actually had, and
Apache POI trims trailing zeros when formatting a numeric cell as text.
The scale difference is a property of the source data's own text
representation passing through unchanged, not something this code does
to the numbers.

### MetLife confirmed clean, and one API-response inconsistency caught along the way

Re-ran MetLife after the Step 6.1 `sourceConstant` prompt fix: `as_of_date`
now comes back as a clean `"2026-02-01"` (still via the same
filename-parsing fallback, still coincidentally correct rather than
correctly-derived from the banner -- see the "MetLife verification"
section above, that risk is unchanged) instead of the messy
explanation-stuffed string from before. All three rows validated with
zero errors and dispatched successfully -- Step 7 now confirmed clean on
both the easy fixture and the one built specifically to be hard.

One inconsistency surfaced by actually reading the response closely: an
absent optional field (`custodian`, not present in MetLife's sheet at
all) showed up in the `/approve` response as `{}`, not `null`. Not a
delivery bug -- `Dispatcher` already converts through
`CanonicalValueJson` before sending, so the team's service genuinely
received `null` for it. The `/approve` HTTP response, though, was
returning the *raw* internal `CanonicalValue` tree (an `AbsentValue` is
an empty Java record, hence `{}`) rather than the same wire-format JSON
that was actually delivered. Two different, both-correct
representations of the same data, shown inconsistently -- exactly the
kind of thing that reads as a bug when comparing what an endpoint shows
against what was actually sent. Fixed by converting `ValidationReport`
through `CanonicalValueJson` at the API boundary
(`MappingController.ValidationSummary`), so the response now always
reflects what was actually delivered, not an internal implementation
detail.

## Step 7.1 hardening (second external review)

A second external review (ChatGPT, static repo review, same rigor as
the Step 6.1 round) went through Step 7 and caught several real
problems -- and, worth being precise about, one suggestion I disagreed
with and didn't implement. Same approach as last time: verify each claim
against the actual code, fix what's genuinely broken, push back
explicitly where a suggestion would break something already deliberately
built and tested, rather than complying by default.

### Fixed

**Approval wasn't atomic or idempotent -- a real, not hypothetical,
double-delivery risk.** The original `/approve` did `findById` +
check-status-is-PENDING in application code, then a separate, plain
`updateStatus` call -- check-then-act, not compare-and-set. Two
concurrent requests could both read PENDING before either write landed,
both proceed to validate and dispatch, and deliver the same batch
twice. Fixed with `MappingProposalRepository.claim`, a single
`UPDATE ... WHERE status = 'PENDING'` whose affected-row-count *is* the
race protection -- if it's zero, someone else already claimed it.

**A failure partway through validate-and-dispatch left things
permanently stuck.** The proposal became APPROVED *before* validation
and delivery ran, with no try/catch around either. Any unexpected
exception (a malformed URI, a serialization failure, anything not
already handled inside `Dispatcher`'s own retry loop) left the proposal
APPROVED with no way to retry through the same endpoint, since it was
no longer PENDING. Fixed by separating the concepts: approval (a
one-time, permanent human decision, claimed atomically) from
validate-and-dispatch (which can legitimately need retrying). A new
`/proposals/{id}/redeliver` endpoint re-runs validate-and-dispatch
against an already-approved proposal without re-claiming it. An
unexpected exception now moves the *batch* (not the proposal) to a new
`PROCESSING_ERROR` status and rethrows, rather than leaving the batch
in the ambiguous `APPROVED` state it was in before the exception --
`/redeliver` has a well-defined thing to retry.

**The source file wasn't re-verified at approval.** The batch stores a
content hash computed at `/propose` time, but `/approve` never
recomputed it -- a file replaced between proposing and approving would
get the old proposal silently applied to new data. Fixed: `/approve`
and `/redeliver` both recompute the hash via the same `FileHasher` and
reject with a clear `SOURCE_CHANGED` message if it doesn't match.

**`conversionNotes` was descriptive, not executable -- a real
silent-corruption risk, not just an inconsistency.** The clearest
concrete case: PIMCO's market rate is stored as a percentage ("5.375")
but the canonical field expects the fraction (0.05375) -- documented as
a known design decision all the way back when `market_rate_book_value.
yaml` was first written. A note saying "divide by 100" never actually
divided anything; `CanonicalRowBuilder` only ever did direct primitive
parsing. The row would pass validation and get delivered *wrong*, with
nothing catching it -- worse than an outright validation failure,
because it looks like success. Fixed with a deliberately narrow typed
transformation: `MappingProposal.TransformationStep` (a flat
`{type, multiplier}` record, not a sealed-interface hierarchy -- see
its javadoc for why a flat shape was chosen specifically to avoid
needing to verify Spring AI's polymorphic-JSON-schema behavior, an
unfamiliar-API risk this project has been burned by more than once this
year), whitelisted and applied by `CanonicalRowBuilder`, checked at
both the structural-validation and row-construction layers, only
`"scale"` implemented (the one concretely-motivated case), and only on
NUMBER fields. The system prompt now tells the agent this capability
exists -- without that, it would have no way to know to use it.

**Retry classification never actually consulted `retryableStatusCodes`.**
The original code checked `terminalStatusCodes`, then fell through to
retryable for *everything* else unconditionally -- an unclassified
redirect, an auth failure a model's config forgot to list as terminal,
anything -- got retried regardless of the configured retryable list.
Extracted into `Dispatcher.classify`, a pure, directly-testable function
(`DispatcherClassifyTest`) implementing the real three-way-plus-default
decision: explicit terminal wins, explicit retryable is honored, and
anything genuinely unclassified falls back to a status-code-range
default (5xx retryable, everything else terminal) rather than either
"retry forever" or "always safe."

**A missing secret produced an empty bearer credential instead of
failing.** `Authorization: Bearer ` (with nothing after it) would
actually get sent to the target. Fixed: checked before any HTTP call,
returns `CONFIGURATION_ERROR` with a clear message naming the missing
environment variable.

**No HTTP timeouts.** A nonresponsive target could block the approval
request indefinitely. Added a 10s connect timeout and 30s per-request
timeout.

**Interrupt handling restored the thread's interrupt flag but kept
retrying anyway.** Fixed to return immediately (a new `INTERRUPTED`
outcome) rather than falling through into another sleep-and-retry cycle.

**Two structural-validator gaps, both agreed with and fixed**: a
`variantValueMap` with no `sourceColumn` to actually read a row's value
from (previously only caught at row-construction time, now caught
before persistence too), and a sum type field with a mapping entry
present but neither `selectedVariant` nor `variantValueMap` set. The
second one is safe to reject structurally in a way the analogous
primitive case isn't -- see below.

**Delivery-log provenance was incomplete.** `delivery_log` only recorded
`import_batch_id`, but a batch can have more than one `mapping_proposal`
over its lifetime (exactly the `/redeliver` scenario this round
introduced). Added `mapping_proposal_id`, `NOT NULL`, to every delivery
attempt.

**Attempt-count inconsistency for the not-implemented path.** Logged
`attempt_number = 1` but returned `attempts = 0` in the response --
small, but the kind of thing that becomes confusing in operational
metrics or a future UI. Now consistent.

### Explicitly disagreed with, and why

**The review suggested the structural validator reject a primitive
mapping with neither `sourceColumn` nor `sourceConstant`.** Not
implemented -- doing so would break a real, already-tested, already
twice-observed-live-and-correct behavior. `custodian` and the
`FixedIncome` sub-fields legitimately have neither, at low confidence,
when the source genuinely doesn't have that data -- confirmed live with
both the JPMC and MetLife fixtures, and there's a dedicated test
(`allowsNeitherSourceColumnNorConstantForAGenuinelyUnavailableField`)
guarding exactly this. The asymmetry with sum type fields (where
"neither mode set" *is* now rejected) is intentional, not an
inconsistency worth resolving the same way: omitting a mapping entry
entirely is how any field -- primitive or sum type -- says "no data for
this." A sum type field that *has* a mapping entry but can't actually
be resolved either way is always malformed, because there's no
legitimate reason to propose an entry you can't resolve. A primitive
field that has neither a column nor a constant is exactly what "I
looked, there's genuinely nothing here" looks like, and CanonicalRowBuilder
already turns that into a hard row-level error for any *required*
field regardless -- optional fields are the only case where "neither"
is legitimately fine, and that's deliberate, not a gap.

### Explicitly deferred, with reasoning

**Client configuration is still not version-pinned.** The canonical
model version-drift check (from Step 6.1) has no `ClientConfig`
equivalent -- `client-configs/*.yaml` has no version or hash concept at
all yet, so there's nothing to pin against. A client's `dateFormat`
changing between proposal and approval could silently change how the
same source value gets interpreted. Fixing this properly means adding a
content-hash concept to `ClientConfig` (parser changes, a persisted
hash column) -- real, buildable work, deliberately not bundled into an
already-large pass. Flagged clearly rather than silently left
unaddressed.

**All-or-nothing vs. valid-rows-only delivery policy.** Currently
always dispatches whatever subset of rows passed validation, reporting
the rest as errors -- a real product decision (some receiving services
need atomic batches, others are fine with partial delivery), not an
accidental default. Needs to become a canonical-model or
delivery-target config option, and a review UI needs to show a reviewer
which policy applies *before* they approve. Not resolved here, matching
how the similar "one proposal per batch or multiple numbered attempts"
question was left open in Step 6.1 -- both are UI/product decisions
better made together with Step 8 than forced in isolation now.

**Durable, row-level validation-report storage.** `ValidationReport`
currently only lives in the HTTP response and application logs --
there's no database table recording per-row construction results,
errors, or which validation run produced them. A real gap for Step 8,
which will need durable access to exactly this data to show a reviewer
what happened, not just what's currently true. Real schema/feature work
on its own, deferred rather than bundled in.

**A transactional outbox, idempotency keys sent to the receiving
service, and moving delivery off the request thread entirely.** The
atomic-claim and `/redeliver` fixes close the *this system's* half of
the idempotency problem (won't claim or process the same proposal
twice) but don't give the *receiving* service anything to dedupe a
retried delivery against beyond the `X-Import-Batch-Id`/
`X-Mapping-Proposal-Id` headers already being sent. A proper
idempotency key plus an outbox pattern (write the intent to deliver
durably, dispatch asynchronously, retry from the outbox rather than
inline with the request) is real infrastructure, appropriately a
distinct piece of work once delivery moves off the synchronous request
path -- already flagged as a known simplification when `Dispatcher` was
first built.

**Concurrency and integration tests** (two simultaneous approvals
resolving to one delivery, a changed workbook actually blocking
approval end-to-end, recovery after a simulated process failure) need
either Testcontainers-backed Postgres or a scripted fake HTTP receiver
capable of returning a specific status sequence -- both real
infrastructure additions, consistent with what Step 6.1 already
identified as missing and didn't build then either.  What *did* get
added this round: `DispatcherClassifyTest` covers the retry-
classification logic directly as a pure function, and
`CanonicalRowBuilderTest`/`MappingProposalStructuralValidatorTest` cover
the transformation and structural-validation additions -- real coverage
of the deterministic logic, just not of concurrency or live HTTP
behavior, which need infrastructure this project has consistently
deferred building speculatively ahead of an actual need.

