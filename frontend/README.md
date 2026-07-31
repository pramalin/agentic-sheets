# agentic-sheets review UI

Step 8b's integrated review UI — see the main project README's
"Design principles" and `ui-notes.md` for why this is React,
integrated into agentic-sheets rather than built as an embeddable
widget.

## Status: queue, review, and edit all built; source samples still pending

Built: the review queue, the review screen (proposed field mappings
with confidence, approve/reject, edit, retry-delivery, validation and
delivery history), and API-key auth matching the backend's
`ApiKeyAuthFilter`.

Edit (`EditProposalPanel`) is a direct JSON editor, not a full
per-field form with dedicated controls for variant maps and
transformations -- a genuinely functional first version, honestly
scoped rather than half-built. See `ui-notes.md`'s Step 8b section.

**Deliberately not built yet**: showing the source spreadsheet's actual
columns and sample values next to the proposed mapping. That data comes
from `/internal/explore/table`, which returns an untyped `JsonNode` on
the backend -- there's no fixed Java record to verify the exact
response shape against, unlike everything else this frontend talks to.
See `ui-notes.md`'s Step 8b section for the full reasoning.

## Running it

```bash
npm install
npm run dev
```

Needs the backend running (`docker compose up -d` from the project
root) — Vite's dev server proxies `/internal/**` to
`http://localhost:8081` by default (see `vite.config.ts`; override with
`AGENTIC_SHEETS_BACKEND_URL` if your backend runs elsewhere).

On first load, enter the same value as `AGENTIC_SHEETS_API_KEY` in the
backend's `.env` (`dev-local-secret` by default).

## A note on the react-router audit warning

`npm install` will flag a high-severity advisory
(GHSA-qwww-vcr4-c8h2) against `react-router`. Checked directly against
the official advisory text: *"This only affects your application if you
are using the unstable RSC APIs."* This app uses plain client-side
routing (`BrowserRouter`, Declarative Mode) — no RSC, no server
actions — so the advisory doesn't apply to this usage. Not silently
ignored; worth re-checking if this app's routing approach ever changes.

## Design system

`src/styles/theme.css` — the palette is deliberately not a new choice,
it's lifted directly from the project's own architecture diagram
(`docs/images/agentic-sheets-drift-resilient-architecture.svg`) so the
docs and the actual tool read as the same product.
