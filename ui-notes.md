# UI notes

The reasoning behind Step 8's design decisions, kept separate from
`mapping-notes.md` deliberately -- that file is about mapping/ADT logic;
this one is about the review UI and how it's meant to be consumed,
starting with a real requirement that arrived before any Step 8 code did.

## Embeddability, decided before any Step 8 code exists

A real requirement surfaced: teams want to incorporate the review UI
*inside their own applications*, not be redirected to a separate
agentic-sheets destination. This changes how Step 8 has to be built from
the start, not something to retrofit onto a standalone app afterward --
worth deciding now, the same way the ADT format and the
no-database-ownership principle got their own design pass before being
built, not after.

**Options considered:**

- **iframe embedding.** Zero architecture change, strong isolation, but
  real UX friction (postMessage-based auto-resize, awkward
  deep-linking) and it visibly reads as "a different site inside a box"
  -- close to the exact thing the requirement says to avoid, even
  without a literal URL redirect.
- **Web Components via Angular Elements** (`@angular/elements`,
  `createCustomElement()`). Compiles Angular components into standard
  custom elements any host page can drop directly into its own DOM.
  Angular's own first-party mechanism for this, confirmed still current
  practice as of mid-2026 (checked via search rather than assumed,
  given this project's repeated experience with stale framework
  assumptions). True DOM integration, no iframe sizing problems, Shadow
  DOM gives CSS isolation without a full iframe boundary.
- **A native React package.** Best possible integration if the
  embedding hosts are React -- natural props, no custom-element
  attribute/property translation at all. Stops being framework-neutral,
  though: a Vue, Angular, or plain-HTML host is simply excluded.
- **Module/Native Federation.** Dynamically loads the UI's compiled
  bundle into the host's own build at runtime, sharing dependencies.
  Solves a bigger problem than the one being asked about here --
  coordinating many independently-deployed app *shells*, not a widget
  embedded into applications this project doesn't control the build of.
- **API-only, bring-your-own-UI.** Already possible today
  (`/internal/mapping/*`), and worth keeping available regardless of
  what else is decided -- but doesn't by itself satisfy "incorporate
  *the UI*," since teams asking for this specifically want to reuse what
  was built, not rebuild it themselves.

## Reconsidered: the framework question changed when the requirement did

The first pass at this decision reasoned from "which teams' host
applications are we embedding into" -- a fair question at the time, and
it pointed toward Angular Elements: keeps the Angular choice already
associated with Step 8, avoids iframe UX problems, and (confirmed via
search) React 19's much-improved custom-element support means a
React-19+ host now consumes an Angular-Elements-produced custom element
cleanly too -- full "Custom Elements Everywhere" compliance, first-class
handling of both primitive and complex (object/array) props.

But the actual goal turned out to be broader: folding `agentic-sheets`
into a *product*, "easily adoptable in any environment" -- unknown
future adopters on unknown stacks, not a fixed set of teams whose
tooling could be surveyed. That's a different question, and it changes
the answer:

- **Angular Elements** bundles the entire Angular runtime into whatever
  page embeds it -- real page weight (order of 100KB+ minified/gzipped
  for the framework alone, before any actual widget code). If the host
  happens to already be React or Vue, that page now loads two full
  framework runtimes for one review widget. React 19's interop
  improvements close the *friction* gap, but don't touch this -- the
  problem was never really about interop friction, it's about which
  framework's runtime tax gets imposed on every adopter who doesn't use
  that framework.
- **A native React package** would need the host to be React at all --
  fine for a known set of internal teams, disqualifying for a product
  with adopters on stacks that aren't known yet and can't be assumed.
- **A lightweight, purpose-built Web Component library (Lit is the
  natural choice)** produces the same standards-based custom element
  interface either full-framework alternative would -- consumable
  identically from a React 19+ host, an Angular host, a Vue host, or
  plain HTML -- with a runtime footprint measured in kilobytes rather
  than tied to a full framework's weight. This is the same pattern
  actual embed-anywhere product widgets use in practice (payment
  widgets, support chat embeds, and similar), not a coincidence -- it's
  the right tool for exactly this job: something an adopter's page has
  to load regardless of whatever else is already on that page.

**Decision: build the embeddable UI as standards-based Web Components
using Lit, not Angular and not React as the implementation.** Not "React
instead of Angular" -- declining to make *either* framework a dependency
of something that's supposed to work everywhere. REST API stays
documented as the escape hatch for adopters wanting full custom control
instead, regardless of which embedding approach they use.

**What this changes about Step 8's build:** review UI components get
written directly as Lit elements rather than as Angular components
compiled to custom elements after the fact via `@angular/elements`. Same
underlying requirements (queue, review screen, approve/edit/reject,
delivery status) -- different implementation foundation, chosen once,
before any Step 8 component code exists rather than after.

## The requirement this surfaces that isn't about the UI at all

Every `agentic-sheets` endpoint is currently explicitly unauthenticated
-- "local development only," per the README's own API table. That was a
reasonable state for a solo prototype exercised by hand with curl. It
stops being reasonable the instant a review widget is embedded inside
someone else's *production* application with real users making approval
decisions.

The natural fit, given "don't redirect to a different site": the host
application passes along an identity it already has (a token from its
own auth/SSO), and the embedded widget forwards that same token to
`agentic-sheets`' API rather than making someone log in a second time
just to review a mapping. This means:

- `agentic-sheets` needs to **validate** an externally-issued token
  (JWT verification against the issuing party's public key/JWKS, most
  likely), not run its own separate login flow.
- The identity carried in that token needs to become the recorded
  `reviewed_by` on an approval -- currently a free-text query parameter
  defaulting to `"manual-api-call"`, fine for a prototype and not fine
  once approvals are real decisions by real people.
- Authorization (which teams/models a given identity is allowed to
  review) is a real question with no answer yet -- likely needs to be
  part of each canonical model's config eventually, extending the
  "system defines the format, teams supply data" pattern already
  established for everything else in `canonical-models/*.yaml`.

This is a real prerequisite for Step 8, not an addition to make
afterward -- an embeddable UI with no auth story isn't actually
embeddable into anyone's real product, it's a demo that happens to be
wrapped in a custom element.

## What "a product for unknown adopters" raises beyond the framework question

Worth naming clearly rather than trying to solve all at once -- each of
these deserves its own dedicated planning pass rather than being decided
as a side effect of the UI framework question:

- **Auth federation, upgraded from "an open question" to "a hard
  requirement," and a bigger one than first framed.** Validating a
  single, known identity provider's tokens is a much narrower problem
  than a product with genuinely unknown future adopters, each
  potentially running their own identity system. Likely needs to
  support *multiple* token issuers/JWKS sources from the start, not
  just whichever one or two are known today.
- **Multi-tenancy and deployment model.** Is `agentic-sheets` a
  centrally-hosted service adopters connect to (their embedded widget
  and backend calls hit an endpoint `agentic-sheets` operates), or
  something each adopter self-hosts in their own infrastructure? That
  answer shapes canonical-model/client-config isolation between
  adopters, the orchestration database's tenancy model, and a lot else.
  Genuinely a separate, large design conversation.
- **The file-based canonical-model/client-config story**
  (`canonical-models/*.yaml`, `client-configs/*.yaml`, loaded from the
  local filesystem) was designed for "our own teams configure their own
  models," not "many customer organizations each configure their own
  models in isolation from each other." Worth a dedicated look at
  whether this needs to become per-tenant, database-backed config
  storage before this scales past a single organization's internal use.

## Open questions, not yet resolved

1. **Which identity provider(s), concretely, does the auth design need
   to support first?** Depends on the multi-tenancy/deployment model
   above -- worth resolving together, not independently.
2. **Where does per-model reviewer authorization live?** A new field in
   each canonical model's config, a separate authorization service, or
   something simpler for now (e.g., "any valid token can review any
   model" as a deliberately loose Step 8 starting point, tightened
   later)?
3. **Does the embedded widget need its own build/versioning story
   independent of the backend's release cycle?** A Lit-based bundle can
   be versioned and served separately from the Spring Boot backend --
   worth deciding whether that independence is wanted now, especially
   given a product needs to ship widget updates without forcing every
   adopter to redeploy in lockstep with the backend.
