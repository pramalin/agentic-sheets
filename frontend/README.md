# agentic-sheets review UI

Step 8b's integrated review UI — see the main project README's
"Design principles" and `ui-notes.md` for why this is React,
integrated into agentic-sheets rather than built as an embeddable
widget.

## Status: Step 8b complete

Everything from the original Step 8 scope is built: the review queue
(with real client/file context, not bare IDs), the review screen
(proposed field mappings with confidence, source-column samples shown
inline next to each field, approve/reject, edit, retry-delivery), and
validation/delivery history rendered usefully rather than as raw JSON.

Edit (`EditProposalPanel`) is a direct JSON editor, not a full
per-field form with dedicated controls for variant maps and
transformations -- a genuinely functional first version, honestly
scoped rather than half-built. See `ui-notes.md`'s Step 8b section for
the full design reasoning, including the source-samples integration
and why it was deferred until the real API shape could be verified
rather than guessed at.

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
