import type { ApiErrorBody, ApproveResponse, ProposalDetail, ProposalQueueEntry } from "./types";

/**
 * The backend's base URL. In development (`npm run dev`), Vite's dev
 * server proxies /internal/** to the real backend (see vite.config.ts)
 * so this can stay empty -- same-origin requests, no CORS to configure.
 * A production build can override this via VITE_API_BASE_URL if the
 * frontend and backend aren't served from the same origin.
 */
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "";

const API_KEY_STORAGE_KEY = "agentic-sheets.api-key";

/**
 * The shared secret for /internal/** (see ApiKeyAuthFilter on the
 * backend) -- a single organization-wide key, not a per-user login,
 * matching the auth story that's actually built. Persisted in
 * localStorage purely for developer convenience (not re-entering it on
 * every reload); this is a real deployed app running in the reviewer's
 * own browser, not a claude.ai artifact, so localStorage is the right
 * tool here.
 */
export function getStoredApiKey(): string {
  return window.localStorage.getItem(API_KEY_STORAGE_KEY) ?? "";
}

export function setStoredApiKey(key: string): void {
  window.localStorage.setItem(API_KEY_STORAGE_KEY, key);
}

const REVIEWED_BY_STORAGE_KEY = "agentic-sheets.reviewed-by";

/** Who's approving/rejecting -- free text, not an authenticated
  * identity (there's no per-user login, see ApiKeyGate). Persisted so
  * a reviewer doesn't retype their name on every decision; purely a
  * courtesy label recorded in mapping_proposal.reviewed_by, not
  * something the backend verifies against anything. */
export function getStoredReviewedBy(): string {
  return window.localStorage.getItem(REVIEWED_BY_STORAGE_KEY) ?? "";
}

export function setStoredReviewedBy(name: string): void {
  window.localStorage.setItem(REVIEWED_BY_STORAGE_KEY, name);
}

/** Thrown for any non-2xx response -- carries the parsed {@code
  * problems} array from the backend's ValidationErrorResponse shape
  * when available, so callers can show the real reason rather than a
  * generic "something went wrong." */
export class ApiError extends Error {
  readonly status: number;
  readonly problems: string[];

  constructor(status: number, problems: string[]) {
    super(problems.length > 0 ? problems.join("; ") : `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problems = problems;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const apiKey = getStoredApiKey();
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(init?.headers ?? {}),
      Authorization: `Bearer ${apiKey}`,
    },
  });

  if (!response.ok) {
    let problems: string[] = [];
    try {
      const body = (await response.json()) as ApiErrorBody;
      problems = body.problems ?? [];
    } catch {
      // Response body wasn't the expected JSON shape (e.g. a 401 from
      // in front of the app entirely, or a network-level failure page)
      // -- fall through with an empty problems list, ApiError's message
      // still falls back to a status-based message in that case.
    }
    throw new ApiError(response.status, problems);
  }

  // Some endpoints (reject, recover-stuck) return no body at all.
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function listProposals(status?: string, limit = 50): Promise<ProposalQueueEntry[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  return request<ProposalQueueEntry[]>(`/internal/mapping/proposals?${params}`);
}

export function getProposalDetail(id: number): Promise<ProposalDetail> {
  return request<ProposalDetail>(`/internal/mapping/proposals/${id}`);
}

export function approveProposal(id: number, reviewedBy: string): Promise<ApproveResponse> {
  const params = new URLSearchParams({ reviewedBy });
  return request<ApproveResponse>(`/internal/mapping/proposals/${id}/approve?${params}`, { method: "POST" });
}

export function rejectProposal(id: number, reviewedBy: string, reason: string): Promise<void> {
  const params = new URLSearchParams({ reviewedBy });
  if (reason) params.set("reason", reason);
  return request<void>(`/internal/mapping/proposals/${id}/reject?${params}`, { method: "POST" });
}

export function redeliverProposal(id: number): Promise<ApproveResponse> {
  return request<ApproveResponse>(`/internal/mapping/proposals/${id}/redeliver`, { method: "POST" });
}
