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

