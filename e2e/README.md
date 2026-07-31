# End-to-end testing (Step 8.5)

Why this exists: every verification of this project's actual working
behavior, across five backend hardening rounds and three UI passes, has
depended on a human running curl or clicking through the app by hand.
That's real proof each time, but not *regression* proof -- nothing
stops a future change from quietly breaking something that already
worked, unless someone happens to re-test that exact path again by
hand. This is the automated layer that catches that class of problem
going forward.

## Design decisions, and why

**Black-box HTTP, not `@SpringBootTest`.** An earlier round of design
discussion assumed API-level E2E testing would need Spring's test
context (the same autoconfiguration machinery this project has
deliberately avoided throughout -- the MCP-client eager-init and
OpenAI-placeholder-key issues from early Spring AI integration). That
assumption was wrong: a test runner that only speaks HTTP to a fully
started `docker compose` stack needs no Spring test infrastructure at
all. It exercises the packaged backend application, real configuration,
real schema initialization, real MCP connectivity, real persistence --
strictly more of the actual system than a partially-assembled Spring
test context would, for less test-framework complexity.

**llmsim, not a real model call.** [pramalin/llmsim](https://github.com/pramalin/llmsim)
answers OpenAI's chat-completions shape from scripted, deterministic
replies -- exactly what a regression test needs (fast, free, and not
subject to a real model's own nondeterminism) without faking anything
else in the pipeline. Real Postgres, real sheets-reader-mcp, real
validator and dispatcher, real fake-target -- only the one genuinely
nondeterministic dependency is replaced.

**Playwright Test as the one test framework, even for pure API
testing.** The golden-path test (`pipeline-api.spec.ts`) uses
Playwright's `request` fixture / `APIRequestContext` and launches no
browser at all -- confirmed by `npx playwright test --list`, which
enumerates the test without installing any browser binary. Using the
same framework Checkpoint B's browser tests will use means one test
runner, one assertion library, one report format, and a straightforward
path from API tests to browser tests later, rather than introducing a
second tool just for the black-box HTTP layer.

**`Script.exactly`, not `repeatingLast`/`cycling`.**
`HoldingsHappyPath.scala` scripts exactly one reply. If the application
under test ever makes a second model call for one proposal -- the exact
class of bug Step 7.4/7.5's hardening rounds found and fixed, a wasted
extra LLM call on a losing concurrent `/propose` -- this fails the test
loudly instead of silently succeeding against a repeated or cycled
reply. `POST /_llmsim/reset` before the test run rewinds the script and
clears the journal, so this doesn't depend on run order or on being the
first proposal ever made against this fixture.

**An isolated Compose project (`-p agentic-sheets-e2e`), not the
default one.** Reusing the ordinary development Compose project would
reuse its `postgres-data` volume too -- and Postgres only runs
`db/init/*.sql` against a *brand-new* volume
(`docker-entrypoint-initdb.d` semantics). A pre-existing dev volume
would silently skip schema initialization and make the suite
nondeterministic, exactly the "the volume already exists" trap this
project's own README has warned about repeatedly for ordinary local
development. `run-golden-path.sh` always tears the isolated project
down afterward (a trap, so this happens on failure too, not just a
clean pass) -- nothing here should ever touch a developer's own
`postgres-data`.

**Business results, not just terminal status.** `DELIVERED` alone
doesn't prove the payload was actually correct -- a defect could
deliver an empty or mis-mapped payload while still returning a
successful HTTP response. The golden-path test checks the actual
proposed field mappings, validation row counts, delivery outcome and
status code, and the durable state in `GET /proposals/{id}` after the
fact, not only what the triggering request's own response claimed in
the moment.

## What's built (Checkpoint A)

- `e2e/llmsim/HoldingsHappyPath.scala` -- one scripted reply, a real
  previously-observed agent response for the JPMC Holdings fixture, not
  synthesized from scratch. Verified as valid JSON matching the real
  `MappingProposal` shape before use.
- `e2e/llmsim/Dockerfile` -- layers that one script on top of
  `ghcr.io/pramalin/llmsim-build`, per llmsim's own documented "Pattern
  A" extension mechanism (a project's own script lives in its own
  package, never copied into llmsim's own repo).
- `compose.e2e.yaml` -- an overlay, not a standalone file; adds
  `llmsim`, points the backend's OpenAI client at it instead of the
  real API.
- `e2e/tests/pipeline-api.spec.ts` -- the golden-path test itself.
  Compiles clean (`npx tsc --noEmit`) and is recognized by Playwright
  (`npx playwright test --list`) -- both verified directly, not just
  written. **Not yet verified to actually pass** -- that needs Docker,
  which this sandbox doesn't have; see the honest status note below.
- `e2e/run-golden-path.sh` -- isolated project, guaranteed cleanup
  (including capturing Compose logs *before* teardown, since the
  containers are gone by the time a separate CI step could otherwise
  query them), explicit readiness polling for `llmsim` specifically
  (it has no Compose-level healthcheck, since it's uncertain whether
  `curl`/`wget` exist inside `eclipse-temurin:21-jre-jammy` to run one
  reliably).
- CI: `.github/workflows/ci.yml` now has three jobs --
  `backend-tests` (unchanged), `frontend-checks` (new: `npm ci`, lint,
  build -- the frontend had no CI coverage at all before this),
  `e2e-golden-path` (new, depends on the other two passing first,
  checks out `sheets-reader-mcp` as a sibling directory since CI has no
  pre-existing local checkout to reuse the way local development does).

### The llmsim version pin -- now confirmed, not a placeholder

`e2e/llmsim/Dockerfile` pins `ghcr.io/pramalin/llmsim-build:0.10.1` --
confirmed directly, not guessed: this version was actually pulled and
built successfully during a real E2E run. Two competing unverified
claims had been floated before that (`0.1.0`, the illustrative example
used in llmsim's own README's Dockerfile walkthrough -- not necessarily
its actual latest tag; and `0.10.1`, asserted without shown
verification). Worth naming plainly: `0.10.1` turned out to be right,
but "turned out to be right" and "was verified before being used" are
different claims, and only the actual build succeeding settled which
one this was.

### A real port-collision bug, found on the first actual run

The first real attempt at running this (see the conversation history
around this file, or just try it again) got further than any of this
project's own internal verification could -- it actually built and
started every image -- and then failed:

```
Error response from daemon: failed to set up container networking:
driver failed programming external connectivity on endpoint
agentic-sheets-e2e-postgres-1: Bind for 0.0.0.0:5432 failed:
port is already allocated
```

`-p agentic-sheets-e2e` gives separate containers, networks, and
volumes -- but does *not* isolate host port bindings. `ports:
"5432:5432"` still tries to bind the literal host port regardless of
project name, and an ordinary dev Compose stack running at the same
time (also mapping 5432) collided directly.

Fixed properly, not by picking a different port and hoping: postgres's
host port was already parametrized in `compose.yaml`
(`${POSTGRES_PORT:-5432}`) from early in this project's own
development; backend's wasn't (hardcoded `8081:8081`), so it got the
same treatment (`${AGENTIC_SHEETS_BACKEND_PORT:-8081}` -- a small,
backward-compatible change to the base file, same default, now
overridable). `run-golden-path.sh` exports both to e2e-specific values
(`15432`, `18081`) before invoking Compose, and passes matching
`E2E_BACKEND_URL`/`E2E_LLMSIM_URL` values through to Playwright, so
there's exactly one place these numbers are decided, not two that could
silently drift apart. llmsim's host port (`18089`) needed no such
mechanism -- it's a brand-new service with no base-file definition to
reconcile against, so its port in `compose.e2e.yaml` is just a plain,
distinct value.

Deliberately *not* fixed by trying to override `ports:` from within
`compose.e2e.yaml`'s merge with the base file -- Compose's list-merge
behavior for that field isn't certain enough to bet a port-collision
fix on, unlike the env-var-interpolation mechanism this reuses, which
was already proven correct by every earlier round of this project's own
`POSTGRES_PORT` usage.

### Honest status, as a real history -- three actual runs, three different findings, culminating in a proven-working pipeline

**Run 1**: built every image, started every container, then failed
immediately on container networking -- `-p agentic-sheets-e2e` isolates
containers/networks/volumes but not host port bindings, and
`postgres`'s hardcoded host port collided with an ordinary dev stack
running at the same time. Fixed by parametrizing `postgres`'s and
`backend`'s host ports (`POSTGRES_PORT`, `AGENTIC_SHEETS_BACKEND_PORT`)
and exporting e2e-specific values from `run-golden-path.sh`, reusing
the exact env-var-interpolation mechanism `compose.yaml` already used
successfully, deliberately not relying on Compose's less-certain
list-merge behavior for `ports` across `-f` files.

**Run 2**: every container reported `Healthy` (including llmsim), the
readiness poll passed, and Playwright actually **executed** the test --
a genuinely different, better class of result than run 1's
infrastructure failure. Two real bugs, both only findable by actually
running this:
- **A 401 on `/propose`.** The test's default API key
  (`e2e-test-key`) was never wired to match anything the backend was
  actually configured with -- it was still reading
  `AGENTIC_SHEETS_API_KEY` from whatever a developer's local `.env`
  happened to contain. Fixed by pinning
  `AGENTIC_SHEETS_API_KEY=e2e-test-key` directly in
  `compose.e2e.yaml` -- the E2E environment shouldn't depend on a
  developer-specific dev secret any more than it should depend on a
  developer's own Postgres volume.
- **A working-directory bug in the cleanup trap.**
  `run-golden-path.sh` `cd`s into `e2e/` to run the test and never
  `cd`s back -- when the test failed and the exit trap fired from
  inside `e2e/`, `cleanup()` looked for `compose.yaml` relative to the
  wrong directory and failed to tear anything down. Fixed by making
  `cleanup()` explicitly `cd` to `$REPO_ROOT` as its first action,
  independent of wherever the trap happened to fire from.

**Run 3**: every single business-logic assertion passed. Propose
succeeded with real, correct field mappings. Approve succeeded.
Validation found valid rows with zero errors. Dispatch succeeded. The
re-fetched durable state agreed: `APPROVED`, `DELIVERED`, one
validation run with zero invalid rows, one delivery log entry with a
`200`. llmsim recorded exactly one call, from the `openai` provider.
**The entire pipeline -- propose through delivery, against a
really-running Compose stack -- is proven working end to end.** The one
failure was the test's own assertion pinning a specific model string
(`gpt-4o-mini`), which doesn't match what's actually configured --
worth naming plainly as a genuinely useful lesson: this project's own
`application.yml` already made a deliberate decision never to hardcode
a model string (*"a hardcoded model string here would just go stale
over time"*, relying on the Spring AI starter's own maintained default
instead), and the test violated that exact principle. Reality proved
the original reasoning right: the default had already moved from
`gpt-4o-mini` to `gpt-5-mini` since the assertion was first written.
Fixed by matching the same philosophy already established in this
codebase -- asserting a non-empty model string was requested (still
catches a real bug: the field silently missing or blank) rather than
pinning a value that was always going to go stale again.

**Run 4**: clean pass. `1 passed (3.6s)`, all containers healthy, clean
teardown -- no further findings, nothing left to fix. Four real runs,
three real bugs found and fixed (none of them foreseeable from static
review alone), ending in a genuinely confirmed working pipeline.

```bash
bash e2e/run-golden-path.sh
```

**Confirmed passing.** `1 passed (3.6s)`, clean run, all containers
healthy, clean teardown. Checkpoint A is done -- not "should pass," not
"internally verified," an actual green run on a real machine against
the actual packaged application. That's the real bar this whole
initiative was trying to clear, and it's cleared.

## What's deferred to Checkpoint B

- `e2e/tests/review-approval.spec.ts` -- one Playwright browser
  journey: seed a pending proposal via the API, open the UI, enter the
  API key, approve it, confirm the UI reflects approved/delivered
  status.
- `e2e/tests/api-key-recovery.spec.ts` -- one Playwright browser test
  for the wrong-key recovery path Step 8c fixed (`SettingsMenu`).
- Chromium installation in CI (`npx playwright install --with-deps
  chromium` -- Chromium only, not all three engines; no meaningful
  value added by Firefox/WebKit coverage for a single-operator internal
  tool).
- Screenshot/trace/HTML-report upload on browser-test failure.
- A `webServer` entry in `playwright.config.ts` to launch the Vite dev
  server as part of the browser test run.

Not started until Checkpoint A is confirmed actually passing -- no
point building browser tests against a pipeline that hasn't been proven
to work yet.

## What's intentionally not covered here

- Every status/transition combination (reject, amend, redeliver, every
  client, every model, every drift/failure state). The golden path is
  one happy path on purpose; add a regression case when a real bug is
  found, or when Step 9 introduces a new critical path worth covering,
  not preemptively.
- Frontend component tests (Vitest + React Testing Library). A browser
  smoke test gives more regression confidence across the frontend/
  backend boundary for a frontend this size; component tests are worth
  adding later, selectively, for logic that's awkward to reproduce
  through the browser (JSON-edit validation, risk-summary calculation,
  status-to-action availability) -- see `ui-notes.md`'s Step 8c section
  for where this was first discussed.
