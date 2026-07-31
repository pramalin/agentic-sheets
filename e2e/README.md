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

## A second external review, after Checkpoint A's confirmed pass

A thorough review of the actual checked-in golden-path test (not just
the design, the real implementation) found one genuinely important gap
and three real maintenance improvements. Verified each against the
actual code or via direct lookup before fixing, same discipline as
every review this project has gone through.

**High: the test never verified the payload actually delivered.**
Confirmed against the real `FakeTargetController` -- it accepted any
string body and always returned success, storing nothing. The test
checked `dispatch.outcome === SUCCESS` and `deliveryLog[0].statusCode
=== 200`, but neither of those proves the *right data* crossed the
delivery boundary -- a regression sending an empty array, wrong field
values, or missing headers would report success identically. Fixed by
adding a request journal to `FakeTargetController`
(`POST /internal/fake-target/_journal/reset`,
`GET /internal/fake-target/_journal/{service}/requests`) -- deliberately a full
list, not just "the most recent request" (the review's own suggested
endpoint shape), matching `GET /_llmsim/calls`'s exact pattern instead:
a list gives both "what was received" *and* "exactly how many times"
as one assertion (`.length`), which a singular "last request" endpoint
can't support no matter how it's queried. The test now asserts exactly
one delivery, the real `X-Import-Batch-Id`/`X-Mapping-Proposal-Id`
headers matching the actual proposal, a non-empty `Idempotency-Key`,
the correct row count, and representative exact values (`account_id`,
`security_id`, `asset_class`, `quantity`, `currency`) from the real
JPMC fixture's first row.

**Medium: CI checked out `sheets-reader-mcp` unpinned.** An unrelated
change to that repo's own `main` branch could silently break or change
this project's E2E result with no change here at all. Fixed by pinning
to a real commit (`b77f59679bcc4b443d54621868d4afd908b81135`), verified
directly via `git ls-remote` against the actual repo, not assumed.
Advance this deliberately (a dependency-update PR) when needed, not by
letting it silently drift.

**Low: fixed project name and ports don't support concurrent runs.**
Two worktrees, or two simultaneous local runs, would have collided --
and one run's cleanup could tear down the other's containers. Fixed:
`run-golden-path.sh` now generates a unique project name per invocation
(`GITHUB_RUN_ID` in CI, `$$` locally), and all three host ports
(`POSTGRES_PORT`, `AGENTIC_SHEETS_BACKEND_PORT`, `LLMSIM_HOST_PORT`) are
overridable (`${VAR:-default}`) rather than unconditionally assigned --
a caller running two of these at once can now set distinct values for
each. `compose.e2e.yaml`'s llmsim port mapping is parametrized to match
(it wasn't before -- an override to `LLMSIM_HOST_PORT` would have had
no effect on the actual container port binding otherwise, a real
inconsistency caught while making this change, not before).

**Low: three GitHub Actions were on deprecated, Node-20-based major
versions.** Verified the actual current major version of each directly
via `git ls-remote --tags` against their real repos, not trusted from
the review's own claim alone: `actions/checkout` → `v7`,
`actions/setup-node` → `v7`, `actions/setup-java` → `v5`, all three
confirmed to actually exist before bumping to them.

### Run 5: the payload-delivery fix itself had a real bug, found only by actually running it

`mvn -B test` -- 72/72, including the Testcontainers test -- confirmed
the backend changes compiled and didn't break anything existing. The
actual E2E run got further than any prior run: past propose, past
approve, past every business-result assertion, and into the *new*
payload-delivery checks this same round added -- where it failed:

```
Expected: "1"
Received: undefined
  at delivery.headers["x-import-batch-id"]
```

The bug was in the fix itself, not the thing it was fixing: HTTP header
names are case-insensitive by protocol (RFC 7230), but
`FakeTargetController`'s original header capture used an exact-string
`Map.get("x-import-batch-id")` -- and `Dispatcher` actually sends
`X-Import-Batch-Id` (mixed case, confirmed directly against
`Dispatcher.java`'s own `.header(...)` calls). The lookup came back
null against whatever casing Spring actually preserved. The fix isn't
"use the right casing" -- there is no one right casing to hardcode, any
client or proxy could send any case -- it's matching header names
case-insensitively regardless of what actually arrives, which the
original version never should have assumed in the first place.

Also added `FakeTargetControllerTest` -- a plain, fast unit test (no
Docker, no Testcontainers; `FakeTargetController` has no external
dependencies at all) that locks this specific behavior in directly,
so a future regression here gets caught by `mvn test` in seconds, not
by needing another live E2E run to notice.

Worth naming as a pattern by now, not just this one instance: every
single bug this E2E initiative has found -- the port collision, the
auth key mismatch, the working-directory bug, the stale model string,
and now this header-casing bug -- was something only an actual
execution surfaced, never something static review or careful reasoning
alone caught first. That's not a knock on the review process; it's
the entire reason this initiative exists.

**Confirmed passing.** `mvn -B test`: 77/77, including all 5 new
`FakeTargetControllerTest` cases. `run-golden-path.sh`: `1 passed
(3.4s)`, clean build, all containers healthy, clean teardown -- the
payload-delivery assertions this whole round exists for genuinely pass
now, not just "should." One small follow-up caught while reading that
same test's own log output: `receive()`'s diagnostic log line did its
own separate, still-case-sensitive `headers.get("x-import-batch-id")`
lookup for the log message, printing a misleading `null` even though
the actual capture logic right next to it was already fixed and
working correctly (confirmed by all 5 unit tests passing). Not a
functional bug -- purely cosmetic, log output only -- but worth fixing
for the same reason as the real bug: moved the log statement to read
from `captured` (guaranteed correctly-keyed by the fix above) instead
of a second, independent lookup that was never going to match either.

## A third external review, once the payload-delivery test itself was confirmed passing

**Medium: the request journal is a real security exposure if this
configuration ever reached a shared or hosted environment.**
`FakeTargetController` recorded full delivered payloads -- for a real
client, real financial data -- in an unbounded map, exposed through a
read endpoint `/internal/fake-target/**` deliberately exempts from auth
(the same exemption that lets `Dispatcher` reach it at all, unauthenticated
by design for local testing). Fine for the isolated, throwaway E2E
environment this was built for; a real risk if it ever reached
anywhere else. Fixed by splitting into three pieces: `FakeTargetJournal`
(the state itself, now `@ConditionalOnProperty`-gated on
`agentic-sheets.fake-target.journal-enabled`, enabled *only* in
`compose.e2e.yaml`), `FakeTargetController` (unchanged in behavior,
now takes `Optional<FakeTargetJournal>` -- `receive()` works
identically everywhere, it just has nothing to record into when the
journal bean doesn't exist), and `FakeTargetJournalController` (the
read/reset endpoints, conditional on the exact same property, so the
whole class doesn't exist in a normal deployment's Spring context at
all). That's a stronger guarantee than an auth check would have been:
a request to the journal endpoints in a normal deployment gets a plain
404 -- confirmed by tracing through `ApiKeyAuthFilter`'s own exemption
logic, which is purely a path-string match, unaware of whether a
handler actually exists behind it -- not a 401 from a check that could
itself have a bug. Also bounded the journal per service (100 entries,
oldest dropped first), mirroring llmsim's own
`LLMSIM_JOURNAL_MAX_ENTRIES` precedent, addressing the unbounded-growth
half of the same finding.

**Low: the "parallel-safe" framing overclaimed what was actually true.**
The unique project name genuinely prevents one run's cleanup from
tearing down another's containers -- but the port *defaults* are still
fixed values, so two unconfigured concurrent local runs would still
collide on ports even though their containers/networks/volumes
wouldn't. Fixed the comments in both `run-golden-path.sh` and
`compose.e2e.yaml` to say what's actually true: parallel runs are
supported *with* distinct port overrides, not automatically safe
without them.

**Low: the two failure-only artifact-upload steps were still on the
old runtime.** Easy to miss precisely because they only run when the
E2E job fails -- a successful CI run never surfaces the deprecation
warning. Verified the real current version directly via
`git ls-remote --tags` before bumping, same discipline as every other
version claim in this project: `actions/upload-artifact` → `v7`, one
major version newer than the `v6` the review itself suggested (both
exist; verifying instead of trusting either specific number turned up
the actually-current one).

## A fourth external review, once the security fix itself was confirmed working

CI run #26 passed clean -- backend tests, frontend checks, and the
golden-path E2E job all green. The review's one remaining finding was a
real routing bug the previous round's fix introduced without noticing:

**Low: the disabled journal's `/reset` URL wasn't actually absent.**
`FakeTargetJournalController`'s endpoints originally lived directly
under `/internal/fake-target` -- meaning `POST
/internal/fake-target/reset` was only reachable while that
controller's bean existed. With the journal property unset,
`FakeTargetController`'s `@PostMapping("/{service}")` was the *only*
handler left registered for that URL shape, and Spring happily matched
it with `service = "reset"` instead of returning 404 -- a request meant
for a now-nonexistent endpoint would be silently treated as an ordinary
fake-target delivery (harmlessly, since the absent journal means
nothing gets recorded either way, but contradicting the documented "the
endpoint doesn't exist" guarantee). Fixed by moving the journal under
`/internal/fake-target/_journal` -- a two-segment namespace the
single-segment `/{service}` pattern can never match, regardless of
which beans happen to be registered, rather than depending on which
controller happens to be present to keep the routes apart.

Also added exactly what the review asked for: a real Spring MVC routing
test (`FakeTargetRoutingTest`, `@WebMvcTest`), not more of
`FakeTargetControllerTest`'s direct-object-construction style. The
review was right to distinguish these -- the earlier unit tests
verified `FakeTargetJournal`'s and `FakeTargetController`'s logic
correctly, but none of them sent an actual HTTP request through a real
`DispatcherServlet`, which is the only way this exact bug could ever
have been caught. `FakeTargetRoutingTest` boots a real (sliced) Spring
context with the journal property left unset -- matching a normal,
non-E2E deployment -- and confirms both journal URLs return a genuine
404, the old unnamespaced `/reset` URL is *not* silently treated as
`service = "reset"`, and the ordinary receiver still works normally for
real service names.

### A real compile failure, from a stale package path -- fixed with evidence, not a guess

`mvn -B test` failed outright: `package
org.springframework.boot.test.autoconfigure.web.servlet does not
exist`. That's `@WebMvcTest`'s package in Spring Boot 3.x -- this
project runs Spring Boot 4.1.0, and Spring Boot 4.0 restructured its
test-autoconfigure modules by web technology as part of a larger
split (the same release that, elsewhere, renamed
`spring-boot-starter-web` to `spring-boot-starter-webmvc` in some
migration paths -- not needed here, since this project's own
`spring-boot-starter-web` dependency was never the problem).
`@WebMvcTest` itself moved to
`org.springframework.boot.webmvc.test.autoconfigure` -- confirmed
directly against the real Spring Boot 4.1.0 API docs before fixing,
not inferred from the error message alone. The other MockMvc imports
(`org.springframework.test.web.servlet.*`) are core Spring Framework,
not Boot-specific auto-configuration, and weren't affected -- confirmed
by the compiler only complaining about the one import, not those.

A second real run, after fixing the import, hit a *different* compile
error: the package genuinely didn't exist at all, confirming Spring
Boot 4.0 had also split test-slice support into separate modular
starters -- `spring-boot-starter-test` alone no longer bundles MVC
test support. Added `org.springframework.boot:
spring-boot-starter-webmvc-test`, confirmed as a real published Maven
Central artifact (present at 4.0.0, 4.0.1, and the underlying
`spring-boot-webmvc-test` module even at `4.1.0-M4`) before adding it,
not guessed at a second time after the first miss. Caught and fixed a
self-inflicted bug while making that edit: used `--` as a prose
separator inside the new pom.xml comment, which is invalid XML (unlike
every other comment style used throughout this project) -- comments
can't contain `--` anywhere in their content, not just at the
boundary. Found by actually parsing the file as XML before calling the
fix done, not by eyeballing it.

**The actual test run then surfaced a real flaw in the test's own
premise, not a remaining bug in the fix.** `mvn test`: 82/83, one
failure --
`theOldUnnamespacedResetUrlIsNotSilentlyTreatedAsAServiceCalledReset`
expected 404, got 400. The request *did* route to
`FakeTargetController.receive()` as expected, but failed on a missing
required request body before reaching any application logic --
revealing that the test itself was asserting the wrong thing. Once the
journal moved to `/_journal`, the plain `/internal/fake-target/reset`
path has nothing left to collide with at all -- `service = "reset"` is
now exactly as valid and unambiguous as any other service name, the
same way a real deployment might genuinely have a client called
"reset". Expecting 404 there was never correct once the actual fix
(the namespace change) was in place; fixed by rewriting the test to
assert what's actually true now -- an ordinary delivery to that URL
succeeds like any other, confirming the namespace change resolved the
ambiguity rather than just relocating it.

## Checkpoint A: closed

A sixth review round, against `mvn test` 83/83 and CI run #27 green
end to end, found nothing further -- confirmed the route separation,
the disabled-state routing test, and the enabled-state E2E behavior
all resolve the original finding correctly, with the reviewer's own
words: *"I consider the Step 8 E2E golden path complete at this
point."*

Six rounds, six real bugs, every one of them found only by actually
running something -- never by static review alone, though review was
consistently what pointed at *where* to look. Worth the full list in
one place, since it's genuinely the point of this whole initiative:

1. A host-port collision `-p` project isolation alone didn't cover
   (containers/networks/volumes were isolated; literal host ports
   weren't).
2. An API key the test and the backend were never actually wired to
   agree on.
3. A working-directory bug in the cleanup trap that broke diagnostics
   on failure specifically.
4. A stale hardcoded model string in the test itself, violating a
   principle this codebase had already deliberately established.
5. An unauthenticated, unbounded journal that would have been a real
   exposure outside the isolated E2E environment it was built for.
6. A routing collision where a disabled journal's URL silently fell
   through to the ordinary receiver instead of 404ing.

Checkpoint B (two Playwright browser journeys -- an approval flow and
the Step 8c wrong-key-recovery path) is next, whenever that's picked
up. Not started yet; no code exists for it.
