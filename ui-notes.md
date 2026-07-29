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
  happen through it.** Every endpoint is still unauthenticated today --
  fine for curl-driven manual testing, not fine once a UI puts a click
  in front of a real person making real approval decisions. Doesn't need
  the multi-tenant/multi-IDP complexity from the deferred list above,
  but "no auth at all" isn't a real answer either once Step 8 exists.
  Worth resolving what a reasonably simple, single-organization auth
  story looks like when Step 8 actually starts, even if it's just "a
  single shared credential" or "whatever this org's existing SSO
  already provides" rather than anything elaborate.
