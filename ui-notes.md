# UI notes

The reasoning behind Step 8's design decisions, kept separate from
`mapping-notes.md` deliberately -- that file is about mapping/ADT logic;
this one is about the review UI and how it's meant to be consumed.

## Scope settled: an integrated UI for agentic-sheets itself, not an embeddable product

Two earlier rounds of design work in this file explored embeddability --
first "teams want to embed this in their own applications" (which
pointed toward Angular Elements), then "actually, fold this into a
product adoptable in any environment" (which reversed that toward
Lit-based Web Components, for good reasons given *that* framing).

The scope has now been settled more narrowly: **the main goal of this
project is processing spreadsheets into canonical data.** The review UI
is Step 8 of *that* pipeline -- an integrated part of agentic-sheets
itself, not a product meant to be embedded into arbitrary third-party
applications. A genuinely reusable, embeddable review widget remains a
reasonable idea, but as its **own separate project** later, built
against agentic-sheets' existing REST API from the outside (the same
"bring your own UI" escape hatch already documented below) rather than
as part of this codebase's Step 8.

This isn't a reversal driven by new information the way the Lit decision
was -- it's a narrower, more concrete goal replacing a broader,
less-defined one. The embeddability reasoning below remains valid *for
that eventual separate project*, not thrown out, just no longer this
project's Step 8 problem to solve.

## Decision: React for the integrated UI

With embeddability out of scope for Step 8, the constraint that made
either Angular Elements or Lit the right call -- not imposing a
framework runtime tax on unknown host applications -- no longer applies.
Step 8 is simply agentic-sheets' own frontend, calling agentic-sheets'
own API, served as part of this project. That's a much more ordinary
decision:

**React** -- the broadest ecosystem and the most mature tooling for
exactly this kind of internal tool (review queues, data tables,
approve/reject workflows, delivery-status views), with no remaining
technical reason to prefer Angular or a Web-Components-first approach
now that embedding into someone else's page isn't a requirement.

**What this changes about Step 8's build:** a standard React
application (Vite or Next.js, not yet decided which -- a smaller
question than the framework itself, worth settling when Step 8 actually
starts), calling `/internal/mapping/*` directly. No custom-element
packaging, no Shadow DOM concerns, no framework-neutrality constraints
on component design.

## What's deferred to the eventual separate embeddable-widget project

Worth keeping this list rather than losing the earlier analysis --
these become relevant again if/when that separate project starts, just
not for Step 8:

- **Framework-neutral packaging** (Lit-based Web Components, per the
  earlier analysis in this file's history -- still the right call for
  that specific problem: embedding into applications on stacks this
  project doesn't control).
- **Multi-tenant auth** (multiple identity providers/JWKS sources,
  since unknown future adopters won't share one identity system).
  Step 8's integrated UI, by contrast, can use a much simpler auth
  story appropriate to an internal tool -- doesn't need to be resolved
  now, and shouldn't be over-built for a requirement that no longer
  applies to this codebase.
- **Multi-tenancy and deployment model** (centrally-hosted product vs.
  self-hosted per adopter) -- only a real question once there are
  actual adopters other than whoever runs agentic-sheets itself.

## Still true regardless of scope

Two things from the earlier design passes hold regardless of whether
the UI is embedded or integrated:

- **The REST API stays the documented, clean contract** --
  `/internal/mapping/*` is how Step 8's own UI will talk to the backend,
  and it's also exactly what the eventual separate embeddable project
  would call from the outside. Nothing about narrowing Step 8's scope
  changes what that API needs to look like.
- **Even an integrated UI needs *some* auth story before real approvals
  happen through it -- now built, in Step 8a.** `ApiKeyAuthFilter`
  checks a single shared secret (`Authorization: Bearer
  <AGENTIC_SHEETS_API_KEY>`) on every `/internal/**` request except
  `/internal/fake-target/**`, failing closed if unconfigured rather than
  silently allowing everything through. Exactly the "single shared
  credential" option floated below as sufficient for now -- deliberately
  not the multi-tenant/multi-IDP complexity the deferred list still
  describes, since that only applies once there are actual adopters
  other than whoever runs agentic-sheets itself.

## Step 8b, first pass: scaffold, design system, review queue

Split into a first pass (this) and a follow-up (the actual review
screen), same reasoning as Step 8a's own split -- the review screen is
substantial enough to deserve its own focused pass rather than being
rushed alongside project setup.

**Real verification, unlike most of this project's backend work.**
`npm`/`npmjs.org` are in this environment's network allowlist, unlike
Maven Central -- so unlike the many rounds of backend code that could
only be verified by the person running `mvn test` and reporting back
(sometimes after a real mistake shipped), `npm run build` and `npm run
lint` both actually ran here and passed before anything was presented.
Worth naming as a genuine difference in how confidently this part of
the project can be checked, not just a footnote.

**The design system reuses the architecture diagram's palette
directly**, not a new choice -- deep navy base, orange for "needs a
human decision," teal for "deterministic / settled," red for "failed."
The status-pill component is the one place this becomes a real,
consistent visual language throughout the tool rather than a one-off
color choice on the diagram. Typography: a serif for headers (matching
the diagram's own title treatment), sans for UI/data density, mono for
IDs and technical values.

**The review queue needed a real backend addition, found while
actually building against it.** `StoredMappingProposal` alone --
proposal ID, batch ID, status -- isn't enough to build a queue a
reviewer could use: no client, no filename, nothing to say what a
proposal is even for. Added `ProposalQueueEntry` and
`MappingProposalRepository.findQueueEntries` (a join with
`import_batch`), and changed `GET /proposals`'s response shape to
match. Worth naming as a pattern, not just this one instance: building
the actual UI against the actual API is revealing real gaps a
backend-only conversation about "what fields does this need" would
likely have missed or guessed wrong about.

**Auth is a simple gate, not a login.** A single shared secret (see
Step 8a's `ApiKeyAuthFilter`), entered once and stored in the browser's
own localStorage -- appropriate here in a way it wouldn't be for a
claude.ai artifact (this is a real deployed app running in the
reviewer's own browser, not code executing inside Claude's own
sandboxed environment). No username, no session; a wrong or expired key
just surfaces as every API call failing with a clear 401, which the
queue page's error state calls out directly rather than a bare
"something went wrong."

### What's built vs. what's next

Built, first pass: project scaffold, design tokens, `StatusPill`, the
API client (typed against the backend's real JSON shapes, verified
against the actual Java records rather than guessed), the review queue
page, and routing through to a proposal's detail data.

Built, second pass: the actual review screen --
`FieldMappingTable`/`ConfidenceBar` (source column/constant, variant
resolution, transformations, and confidence for every proposed field),
working approve/reject through `ReviewActions` (with a persisted
"reviewed by" name and a reason field for rejection), a retry-delivery
action for a batch stuck at `DELIVERY_FAILED`/`PROCESSING_ERROR`, and
`ValidationHistory`/`DeliveryHistory` rendering the durable history Step
8a's backend work added.

**Deliberately deferred, and why**: showing the source spreadsheet's
actual columns and sample values next to the proposed mapping -- the
layout this document called the right structural choice from the start.
That data comes from `/internal/explore/table`, which returns a raw,
untyped `JsonNode` on the backend (see `SpreadsheetExplorerController`)
rather than a fixed Java record -- there's nothing to verify the exact
response shape against the way every other type in this frontend was
verified against a real `record`. Traced through the one place the
backend itself actually parses this response
(`MappingProposalService.extractColumnHeaders`) and confirmed
`columns[].header` is real, but the type/sample fields a genuinely
useful side-by-side view would need aren't confirmed. Given this
project's repeated cost of guessing at unfamiliar shapes, built what's
fully verified (the proposal side) rather than the row-by-row samples,
and left this named as the next concrete piece rather than quietly
dropped.

**"Edit" is now built too** -- `POST /internal/mapping/proposals/{id}/amend`,
`ProposalDecisionService.amendProposal`, and a new `SUPERSEDED` status
(deliberately distinct from `REJECTED` -- "corrected" and "wrong" are
different facts worth keeping distinct in the audit trail; this exact
distinction was first floated as a future need in `mapping-notes.md`'s
Step 7.4 section, before there was an actual UI to build it against).
Structural validation reused from the agent path
(`MappingProposalService.validateEdited`) -- a human-edited field path
or variant name can be just as malformed as a model's, and correctness
doesn't depend on who wrote the content.

The frontend side (`EditProposalPanel`) is honestly scoped, not a full
per-field form: a direct JSON editor pre-filled with the current
proposal, not individual inputs for every property. A fully per-field
editing UI (dedicated controls for `variantValueMap` entries,
`transformations` arrays, and so on) is real, separate UI work in its
own right -- this is a genuinely functional first version, not a
placeholder, but worth naming as a lower-fidelity interaction than
`FieldMappingTable`'s read view.
