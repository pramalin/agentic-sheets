import { BrowserRouter, Routes, Route, Link } from "react-router-dom";
import { ApiKeyGate } from "./components/ApiKeyGate";
import { QueuePage } from "./pages/QueuePage";
import { ProposalDetailPage } from "./pages/ProposalDetailPage";

// BrowserRouter here means the plain client-side (Declarative Mode)
// router -- no RSC, no server actions. Worth noting explicitly because
// `react-router` currently has an open advisory (GHSA-qwww-vcr4-c8h2)
// that only affects the unstable RSC APIs; this app's usage isn't
// exposed to it. See frontend/README.md for the fuller note.
export function App() {
  return (
    <ApiKeyGate>
      <BrowserRouter>
        <header
          style={{
            borderBottom: "1px solid var(--border)",
            padding: "14px 24px",
          }}
        >
          <Link to="/" style={{ fontFamily: "var(--font-display)", fontSize: 18, fontWeight: 700, textDecoration: "none" }}>
            agentic-sheets
          </Link>
        </header>
        <Routes>
          <Route path="/" element={<QueuePage />} />
          <Route path="/proposals/:id" element={<ProposalDetailPage />} />
        </Routes>
      </BrowserRouter>
    </ApiKeyGate>
  );
}
