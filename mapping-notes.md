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

Not yet tried live: `holdings_metlife_20260201.xlsx`, which exercises
`sourceConstant` (banner-derived `as_of_date`), a genuinely missing
`custodian`, and an unmapped `Trader Notes` column — none of which
JPMC's deliberately-easy file tests. Worth doing before treating Step 6
as proven beyond the easy case.

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

