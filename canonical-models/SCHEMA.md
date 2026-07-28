# Canonical model configuration format

The system defines this format once; every team supplies a *configuration*
in it, not code. A configuration is a tree of Algebraic Data Types: bigger
types built out of smaller ones by combining them with AND or OR.

## Type kinds

- **primitive** — `String`, `Number`, `Date`, `Boolean`. Written as a bare
  name for defaults, or as `{ type: <primitive>, format: <string> }` to
  override the wire/output format explicitly (e.g. `Date` defaults to
  ISO-8601 `yyyy-MM-dd`; a team whose receiving service expects something
  else can say so). This is the *canonical/output* format — what the
  system renders when it serializes a value into the JSON payload sent to
  a team's service. It has nothing to do with how a client's raw
  spreadsheet happens to format its own dates — see "Source conventions"
  below for that.
- **record** (product type, AND) — a fixed set of named fields, every one
  of which is present in a valid value. `field: Type` for required,
  `field: Type?` for optional (`Type?` means `Option[Type]`).
- **sum** (sum type / tagged union, OR) — a named set of *variants*, of
  which exactly one is present in a valid value, never zero, never more
  than one. Each variant is itself a record — often with fields of its
  own (`FixedIncome` below), sometimes with none at all, which makes a
  sum type of all-empty variants behave like a plain enum (`Currency`
  below).

## References

A field's type can name another type defined in the same `types:` map.
That's how records nest, and how a sum type gets used as a field's type
inside a record (`asset_class: AssetClass`).

## Source conventions (per client)

A client's own file conventions — what format they write dates in, for
instance — are a property of *that client*, not of the canonical model.
`holdings.yaml` is shared by every client that feeds holdings data; it
can't hold a single input date format that's simultaneously right for
JPMC (native Excel dates, no parsing needed at all) and PIMCO (text
strings in `MM/dd/yyyy`). So this is a separate, much smaller config,
one file per client:

```yaml
# client-configs/<client>.yaml
client: <client id, matches the filename convention>
dateFormat: <Java DateTimeFormatter pattern, e.g. MM/dd/yyyy>
```

This isn't really an ADT — there's no product/sum structure to a handful
of parsing hints — so it stays flat. It'll likely grow more fields over
time (decimal separator, thousands separator, a default currency) as more
client quirks show up, but nothing here needs the record/sum machinery
the canonical models do.

## Document shape

```yaml
model: <name>
version: <int>
types:
  <TypeName>:
    kind: record
    fields:
      <fieldName>: <TypeName-or-primitive>[?]
  <OtherTypeName>:
    kind: sum
    variants:
      <VariantName>:
        kind: record
        fields: {}   # can be empty, or have its own fields
root: <TypeName>       # the type of one canonical row
```

## Target service

The system has no knowledge of any team's database schema, and never
writes to one — after a human approves a mapping, the system validates
the result against this file's ADT, serializes it, and hands it to a
service *the team owns and runs*. What happens to it after that (which
relational tables, which columns, whether it's normalized or denormalized)
is entirely outside this system's concern.

```yaml
target:
  service: <human-readable name, used in logs/audit>
  transport: rest | mcp
  endpoint: <URL>
  tool: <MCP tool name>        # only when transport: mcp
  auth:
    type: api-key | oauth2-client-credentials | mtls
    secretRef: <name of an env var / secret-store entry — never a literal secret>
  delivery:                    # optional -- all fields below have defaults
    maxAttempts: 5
    backoff: exponential       # exponential | fixed
    initialDelaySeconds: 2
    maxDelaySeconds: 60
    retryableStatusCodes: [502, 503, 504]   # REST only; defaults to 5xx + connection/timeout failures
    terminalStatusCodes: [400, 409, 422]    # REST only; defaults to 4xx -- not retried, surfaced as "rejected" for a human to look at
```

`delivery` is per-team, same as `transport` — one team's service might be
flaky and worth retrying hard; another might treat any non-2xx as a
definitive business rejection that retrying can't fix. The defaults above
(retry on 5xx/network failure, treat 4xx as terminal) cover the common
case so most teams never need to write this block at all. For
`transport: mcp`, the same retryable/terminal distinction applies, but
the signal is coarser: a connection-level failure (timeout, unreachable)
is retryable; a tool result with `isError: true` is terminal by default,
since MCP doesn't have anything as structured as HTTP status codes to
classify by.

`transport` is chosen per team, not globally — a team that already runs
MCP infrastructure gets a tool call; a team that just wants a webhook
gets a plain REST `POST`. Either way, the request body's shape *is* this
file's `root` type, rendered as JSON — the ADT config is simultaneously
the mapping target, the structured-output schema the agent is bound to,
and the wire contract the team's service receives. One definition, three
uses.

Every delivery includes the originating `import_batch` id (as a header for
REST, as a tool argument for MCP) so the team's service can deduplicate a
retried delivery — the system will retry on a transient failure (timeout,
5xx, connection refused), and a receiving service that isn't idempotent on
that id will double-write on retry.

For this iteration, `secretRef` names an OS environment variable, sourced
from a `.env` file loaded via `docker-compose`'s `env_file` — the same
pattern `sheets-reader-mcp` already uses. Not an external secret manager
(Vault, AWS Secrets Manager, ...) yet; revisit if/when that's actually
needed rather than building it speculatively now.

## Loading & reload

Configuration here is expected to change often, not to be set once and
forgotten — which means *how* it's loaded matters as much as its shape.
One rule: **exactly one component parses these files, ever.** A
`CanonicalModelRegistry` reads `canonical-models/*.yaml`, parses each into
the typed `CanonicalType` tree (`backend/src/main/java/com/alai/agenticsheets/canonical/`),
validates it (every type reference resolves, `root` exists, no dangling
names), and holds the result as one immutable `CanonicalModel` object per
team. Every downstream consumer — the agent's prompt builder, the
structured-output schema the agent is bound to, the deterministic
validator, the dispatcher that serializes the outbound payload — reads
that typed object. None of them ever parses YAML or JSON text themselves.

Reload happens on a schedule (the same mechanism as the inbox poller,
Step 9), and is atomic: a new file is fully parsed and validated into a
new `CanonicalModel` *before* anything swaps it in. If that fails, the
error is logged and the previous good `CanonicalModel` keeps serving
traffic — reload never leaves the registry half-applied, and a mistake in
one team's file can't affect any other team's pipeline.

`version` is load-bearing. A `mapping_proposal` records which version it
was generated against, so a config change mid-review doesn't retroactively
invalidate something already pending approval. A `mapping_memory` cache
entry is keyed on version too, so bumping a team's config naturally
invalidates their old cached mappings instead of silently reusing one
built against fields that no longer exist or mean something different now.

## Why this matters beyond tidiness

A sum type isn't just documentation — it's an assertion the system can
enforce mechanically at three points: (1) when the agent proposes a
mapping, the structured-output schema it's bound to only has slots for
one variant's fields at a time, not all variants' fields unioned together
with nulls; (2) the deterministic validator rejects a proposal that fills
in fields from two variants at once, or from none; (3) exhaustive
pattern-matching over variants (Java 21 sealed-interface `switch`) means
adding a new variant later is a compile error everywhere the old code
didn't handle it, not a silent runtime gap.
