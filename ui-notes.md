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

**The source-samples gap is now closed, once the real shape was
confirmed rather than guessed.** `/internal/explore/table` returns a
raw, untyped `JsonNode` on the backend -- there was nothing to verify
the exact response shape against the way every other type in this
frontend was verified against a real Java `record`. Rather than guess,
asked for and got a real response pasted back:
`{worksheet, headerRowIndex, firstDataRowIndex, lastDataRowIndex,
detectionConfidence, columns: [{header, inferredType, nullRate,
sampleValues}]}`. One detail that would have been easy to get wrong by
guessing: `sampleValues` are always strings in the real response, even
for `NUMBER`/`DATE` columns -- the MCP tool stringifies everything, and
`SourceColumn`'s type reflects that reality rather than a more
"correct"-looking union that doesn't match what actually comes back.

Placement matters more than the fetch itself: samples show up *inline*
in `FieldMappingTable`, next to the matched source column in each
row, not as a separate panel the reviewer would have to cross-reference
manually against the proposed mapping. That's the actual decision a
reviewer is making -- "does this proposed field make sense given what's
really in the file" -- and the layout should embody that directly. The
fetch is deliberately independent of the main proposal-detail fetch: a
failure or delay here degrades to "no samples shown," not a broken
review screen, since this is a genuine enhancement, not something the
core approve/reject workflow depends on.

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

## Step 8b UX/operational-safety review, and what came of it

A thorough external review of the built UI (not just the code, the
actual reviewer-facing behavior) found several confirmed bugs, not just
style preferences -- worth verifying each against the real code before
fixing anything, same discipline as every backend review in this
project.

**Confirmed and fixed:**

- **No reachable "Change API key" action.** `QueuePage`'s error state
  told a reviewer to "check the API key in Settings," but no Settings
  control existed anywhere -- `ApiKeyGate` only ever showed the entry
  form once, before a key was stored. Added `SettingsMenu` (reviewer
  name, change key, clear credentials), reachable from the header on
  every screen.
- **Sample-load failures were silently swallowed.** `describeTable(...)
  .catch(() => setSourceColumns({}))` made a genuine fetch failure
  indistinguishable from "this proposal has no source samples" -- a
  reviewer had no way to tell "no evidence available" from "evidence
  intentionally absent." Now tracked as a distinct `sampleError` state
  with a visible, non-blocking banner and a Retry action.
- **One shared error state for two different failures.**
  `ProposalDetailPage` used the same `error` state for the initial load
  and for a failed redelivery attempt -- a failed retry could show
  "Couldn't load proposal" while the proposal was still correctly
  loaded and visible underneath. Split into `loadError` and
  `redeliveryError`.
- **Confidence color didn't match its own documented intent.**
  `ConfidenceBar`'s comment said low confidence should read as "needs a
  closer look," not an alarm -- but the code colored anything below 60%
  in `--accent-danger`, the same red a genuine validation/delivery
  failure uses. Added `--accent-pending-strong`, a darker orange
  distinct from both the ordinary pending color and danger red, so
  confidence stays epistemic ("inspect this") rather than looking like
  a system failure at any level.
- **Decision vs. delivery status was unlabeled.** Two bare `StatusPill`s
  side by side (e.g. "Approved" next to "Delivery failed") required the
  reviewer to infer which pill meant what. Now explicitly labeled
  "Decision:" and "Delivery:".
- **No risk summary before a one-click approval.** Added a compact
  summary line (fields mapped, count below 60% confidence, count
  unmapped, samples-availability) at the top of the Decision section --
  cheaper than the review's suggested full confirmation-gate (see
  deferred list below), but genuinely surfaces the same information a
  reviewer would want before clicking Approve.
- **Reject had no way back.** Once the reason input appeared, there was
  no cancel path short of actually rejecting. Added Cancel; also hid
  the Approve button while the reject-reason input is open, so a
  misclick can't approve something mid-reject.
- **The JSON editor only validated on Save.** Now validates on every
  keystroke, with an inline warning and Save disabled while invalid,
  not just a Save-time error box discovered after the fact. Added
  "Reset changes" and a `beforeunload` warning for unsaved edits.
- **Queue had no "needs attention" view.** Only "Needs review" and
  "All" existed; the review correctly noted "Failed" is likely more
  operationally valuable than "All" once a queue has real history.
  Needed an actual backend change to express properly: `GET /proposals`
  previously only accepted one status, and "needs attention" is six
  different statuses (`PROPOSING_ERROR`, `VALIDATION_FAILED`,
  `PROCESSING_ERROR`, `DELIVERY_FAILED`, `SOURCE_CHANGED`,
  `CONFIG_CHANGED`), not one -- generalized
  `MappingProposalRepository.findQueueEntries` to accept a status list
  (`WHERE status IN (...)`, the same dynamic-IN-clause idiom already
  used by `claimForProcessing`), which also let `findAll` -- dead code
  since `findQueueEntries` superseded it in the first Step 8b pass and
  nothing had called it since -- finally get removed.
- **Accessibility: the mapping table and queue are `div`/`span` grids,
  not semantic `<table>`s.** Restructuring to real tables would also
  mean reworking the CSS layout (table layout and grid layout don't mix
  cleanly) -- took the review's own offered alternative instead: ARIA
  `role="table"`/`"row"`/`"columnheader"`/`"cell"` on the existing grid
  structure, giving a screen reader the same column-association
  information without the layout rework.
- **No responsive fallback.** Fixed-column CSS grids with
  `overflow: hidden` meant content actually got clipped on narrow
  screens, not just cramped. Took the review's explicitly-offered
  "at minimum" bar rather than a full card-stacking redesign:
  horizontal scroll with an explicit `min-width` on both the queue and
  the mapping table, so nothing is ever silently cut off.

**Explicitly deferred, with reasoning:**

- **A full confirmation-gate modal** for risky approvals (low
  confidence, unmapped columns, sample-load failure, transformations
  present). The summary line above surfaces the same information
  cheaply; gating the approve button behind a second confirmation step
  is a real UX decision (when exactly should it trigger, what should it
  say) worth its own consideration rather than building quickly here.
- **A full card-stacking responsive redesign.** The horizontal-scroll
  fallback meets the review's own "at minimum" bar; a genuinely
  reflowed mobile layout is a bigger design pass.
- **Edit-panel diff view, per-field backend-error attribution, and
  keeping a superseded proposal's edit state accessible after
  navigating away.** All real, all bigger asks than the cheap wins
  (Reset, continuous validation, unsaved-changes warning) already
  built.
- **In-app navigation blocking for unsaved edits** (only the browser-
  level `beforeunload` was added, not blocking React Router navigation
  like clicking "Back to queue" mid-edit). That needs a specific
  react-router API this project hasn't verified against a real build --
  unlike the router usage already proven out elsewhere in this app.
  Narrower but confirmed correct beats broader but guessed at, given
  this project's repeated cost of getting exactly this kind of thing
  wrong (see the `pom.xml`/Testcontainers episodes in `mapping-notes.md`
  for what guessing at an unfamiliar library's API actually cost, in a
  different but analogous situation).
- **Search, additional queue filters ("Approved but not delivered",
  etc.), result counts per filter, and pagination.** "Needs attention"
  was the one addition built, since the review specifically called it
  out as more valuable than "All" -- the rest is real, separate feature
  work.
- **Hard-requiring a rejection reason.** Left optional, matching the
  backend's own existing design (reason is nullable in
  `mapping_proposal`) -- a soft nudge via placeholder text rather than
  blocking rejection outright, since there are legitimate "just wrong,
  no detailed reason" cases.
- **A "Backend connection status" indicator** in the header. Would need
  new global connectivity-tracking state this project doesn't have yet
  (something has to own "was the last API call successful"); a
  reasonable idea, not attempted here.
- **Automated frontend tests** (React Testing Library, Playwright).
  Genuinely significant new test infrastructure, similar in kind to the
  Testcontainers work on the backend side -- deserves its own dedicated
  pass, not squeezed into a UX-fix round. The review's specific
  suggested coverage (approval, rejection, wrong-key recovery, silent
  sample-fetch failure, superseding edits, retry delivery) is a
  reasonable starting scope whenever that pass happens.
- **README screenshots.** Attempted a headless-browser screenshot
  earlier in this project (see the architecture-diagram work) and hit
  the same sandbox network restriction that blocked Playwright's
  browser-binary download then; that restriction hasn't changed.
