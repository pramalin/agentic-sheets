# UI notes

The reasoning behind Step 8's design decisions, kept separate from
`mapping-notes.md` deliberately -- that file is about mapping/ADT logic;
this one is about the review UI and how it's meant to be consumed,
starting with a real requirement that arrived before any Step 8 code did.

## Embeddability, decided before any Step 8 code exists

A real requirement surfaced: teams want to incorporate the review UI
*inside their own applications*, not be redirected to a separate
agentic-sheets destination. This changes how Step 8 has to be built from
the start, not something to retrofit onto a standalone Angular app
afterward -- worth deciding now, the same way the ADT format and the
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
  custom elements any host page can drop directly into its own DOM --
  React, Vue, another Angular version, or plain HTML, no framework
  lock-in on the embedding side. Angular's own first-party mechanism for
  this, still the current recommended approach as of mid-2026 (checked
  rather than assumed, given this project's repeated experience with
  stale framework assumptions). True DOM integration, no iframe sizing
  problems, Shadow DOM gives CSS isolation without a full iframe
  boundary.
- **Module/Native Federation.** Dynamically loads the UI's compiled
  bundle into the host's own build at runtime, sharing dependencies.
  Solves a bigger problem than the one we actually have -- coordinating
  many independently-deployed app *shells*, not a handful of teams
  wanting to embed one review widget. Real build-tooling coordination
  overhead for no corresponding benefit at this scale.
- **API-only, bring-your-own-UI.** Already possible today
  (`/internal/mapping/*`), and worth keeping available regardless of
  what else is decided -- but doesn't by itself satisfy "incorporate
  *the UI*," since teams asking for this specifically want to reuse what
  was built, not rebuild it themselves.

**Decision: Angular Elements as the primary embedding mechanism, REST
API kept clean and documented as the escape hatch for teams wanting
full custom control instead.** Keeps the Angular choice already made
for Step 8, avoids the iframe UX problems, doesn't require coordinating
build tooling with every embedding team's own stack.

## The requirement this surfaces that isn't about the UI at all

Every `agentic-sheets` endpoint is currently explicitly unauthenticated
-- "local development only," per the README's own API table. That was a
reasonable state for a solo prototype exercised by hand with curl. It
stops being reasonable the instant a review widget is embedded inside
another team's *production* application with real users making approval
decisions.

The natural fit, given "don't redirect to a different site": the host
application passes along an identity it already has (a token from its
own auth/SSO), and the embedded widget forwards that same token to
`agentic-sheets`' API rather than making someone log in a second time
just to review a mapping. This means:

- `agentic-sheets` needs to **validate** an externally-issued token
  (JWT verification against the issuing team's public key/JWKS, most
  likely), not run its own separate login flow.
- The identity carried in that token needs to become the recorded
  `reviewed_by` on an approval -- currently a free-text query parameter
  defaulting to `"manual-api-call"`, which was fine for a prototype and
  isn't fine once approvals are real decisions by real people.
- Authorization (which teams/models a given identity is allowed to
  review) is a real question with no answer yet -- likely needs to be
  part of each canonical model's config eventually (which team's
  reviewers can see and approve proposals for *this* model), extending
  the "system defines the format, teams supply data" pattern already
  established for everything else in `canonical-models/*.yaml`.

This is a real prerequisite for Step 8, not an addition to make
afterward -- an embeddable UI with no auth story isn't actually
embeddable into anyone's real product, it's a demo that happens to be
wrapped in a custom element.

## Open questions, not yet resolved

1. **Which identity provider(s)?** Different embedding teams may already
   use different SSO systems. Does `agentic-sheets` need to support
   multiple token issuers/JWKS sources, or does it standardize on one
   (e.g., requiring every embedding team to front their token behind a
   common format), pushing translation work onto the host application
   instead?
2. **Where does per-model reviewer authorization live?** A new field in
   each canonical model's config, a separate authorization service, or
   something simpler for now (e.g., "any valid token can review any
   model" as a deliberately loose Step 8 starting point, tightened
   later)?
3. **Does the embedded widget need its own build/versioning story
   independent of the backend's release cycle?** Angular Elements
   bundles can be versioned and served separately from the Spring Boot
   backend -- worth deciding whether that independence is wanted now or
   whether shipping both together is fine at this scale.
