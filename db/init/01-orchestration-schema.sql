-- Orchestration schema for agentic-sheets. This is NOT canonical data --
-- holdings/market_rate_book_value rows never live here. Each team owns
-- its own database; this system only ever calls out to a team's service
-- after a human approves a mapping. See canonical-models/SCHEMA.md and
-- mapping-notes.md for the full reasoning.
--
-- Plain SQL, not a migration framework, same reasoning as
-- agentic-analytics: a handful of tables this project owns end to end
-- doesn't need migration tooling's overhead yet. Revisit if this schema
-- ever needs to evolve across environments without a full rebuild.

CREATE TABLE import_batch (
    id                  BIGSERIAL PRIMARY KEY,
    model_id            TEXT NOT NULL,
    client_id           TEXT NOT NULL,
    source_filename     TEXT NOT NULL,
    content_hash        TEXT NOT NULL,
    -- Added after Step 6's external review caught a real bug: without
    -- this, two different worksheets in the same workbook -- or the same
    -- file submitted a second time for a different model/client -- would
    -- collide onto the same batch, silently attaching a proposal to a
    -- batch whose recorded model_id/client_id didn't match what was
    -- actually mapped. Filename + content hash alone identify a *file*,
    -- not a unit of work.
    worksheet           TEXT NOT NULL,
    config_version      INTEGER NOT NULL,
    -- Informal for now (PENDING / MAPPED / PROPOSING / PROPOSING_ERROR /
    -- APPROVED / REJECTED / PROCESSING / VALIDATION_FAILED /
    -- PROCESSING_ERROR / SOURCE_CHANGED / CONFIG_CHANGED / DELIVERED /
    -- DELIVERY_FAILED), not a DB-level enum or CHECK
    -- constraint -- easier to iterate on the state machine in application
    -- code while this is still actively evolving.
    status              TEXT NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Full identity, not just the file: same file + same worksheet +
    -- same model + same client + same config version means "already
    -- processed, reuse the batch." Any of those differing is a distinct
    -- unit of work, even against the identical bytes.
    UNIQUE (source_filename, content_hash, worksheet, model_id, client_id, config_version)
);

CREATE INDEX idx_import_batch_status ON import_batch (status);
CREATE INDEX idx_import_batch_model_client ON import_batch (model_id, client_id);

CREATE TABLE mapping_proposal (
    id                  BIGSERIAL PRIMARY KEY,
    import_batch_id     BIGINT NOT NULL REFERENCES import_batch (id),
    -- Pinned at creation time -- a canonical model's version changing
    -- mid-review must not retroactively affect a proposal already
    -- pending approval. See SCHEMA.md's "Loading & reload" section.
    config_version      INTEGER NOT NULL,
    proposal            JSONB NOT NULL,
    -- PENDING / APPROVED / REJECTED / SUPERSEDED (a human edited this
    -- proposal rather than approving or rejecting it -- see
    -- ProposalDecisionService#amendProposal; deliberately distinct
    -- from REJECTED, since "corrected" and "wrong" are different
    -- facts worth keeping distinct in the audit trail), informal for
    -- the same reasons as
    -- import_batch.status above.
    status              TEXT NOT NULL DEFAULT 'PENDING',
    reviewed_by         TEXT,
    reviewed_at         TIMESTAMPTZ,
    -- Step 8: a human's stated reason for rejecting -- null for an
    -- approval, or for a proposal nobody has reviewed yet.
    rejection_reason    TEXT,
    -- Step 10: AGENT (a real model call produced this -- the default,
    -- and the only possibility before this column existed) / MEMORY
    -- (reused from a prior clean-validated approval, no model call) /
    -- HUMAN_AMENDMENT (a reviewer edited a proposal via /amend --
    -- distinct from AGENT because the content is no longer purely
    -- what the model said, same reasoning SUPERSEDED already applies
    -- to status above).
    origin               TEXT NOT NULL DEFAULT 'AGENT',
    -- Set only when origin = MEMORY -- which mapping_memory row this
    -- reuse came from. Nullable: most proposals aren't memory reuses.
    mapping_memory_id    BIGINT,
    -- Step 10: computed once at propose time (whichever path --
    -- AGENT, MEMORY, or a human amendment inherits the proposal it
    -- amended) and stored here so promotion at approval time can read
    -- them back rather than re-computing via a second describe_table
    -- call. Nullable only in the sense that old rows from before this
    -- column existed won't have them -- every new proposal always
    -- does.
    column_fingerprint         TEXT,
    client_config_fingerprint  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mapping_proposal_batch ON mapping_proposal (import_batch_id);
CREATE INDEX idx_mapping_proposal_status ON mapping_proposal (status);

-- Database-level backstop for "at most one active proposal per batch"
-- (see MappingController.propose and mapping-notes.md) -- application
-- code checks for an existing PENDING proposal before inserting a new
-- one, but this index makes the invariant hold even if that check ever
-- has a bug or a race the application code doesn't catch. A partial
-- index (only over PENDING rows) rather than a plain unique constraint
-- on import_batch_id, since a batch legitimately accumulates many
-- proposals over time (rejected, then re-proposed) -- only ever one
-- PENDING at a time.
CREATE UNIQUE INDEX uq_mapping_proposal_active_batch
    ON mapping_proposal (import_batch_id) WHERE status = 'PENDING';

-- Step 10: a reusable mapping, learned from a prior clean-validated
-- human approval -- see mapping-notes.md's Step 10 section for the
-- full design reasoning (three external review rounds, none of which
-- is repeated here).
--
-- Deliberately scoped narrower than "reuse the whole proposal
-- verbatim": mapping_memory only ever stores mappings safe to reapply
-- against a *different* file with the same structure --
-- sourceColumn/transformations/variantValueMap-based fields, never a
-- mapping containing sourceConstant (a banner-derived literal value
-- specific to the file it came from -- reusing it would silently
-- apply the previous file's data as the current file's fact) or a
-- data-derived selectedVariant (CanonicalRowBuilder trusts this
-- immediately with zero row-level verification, unlike
-- variantValueMap, which validates every row's own discriminator
-- value -- confirmed by reading that class directly, not assumed).
-- See MappingMemoryEligibility for the actual check.
CREATE TABLE mapping_memory (
    id                          BIGSERIAL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    -- Stands in for the "feed" concept -- always available on both the
    -- manual /propose path and Step 9's scanner path (a feedType, by
    -- contrast, only exists for scanner-originated proposals). Serves
    -- the same distinguishing purpose an external review asked for:
    -- two different reports from the same client rarely share a
    -- worksheet name, even when their column layouts coincidentally
    -- overlap.
    worksheet                   TEXT NOT NULL,
    model_id                    TEXT NOT NULL,
    model_version               INTEGER NOT NULL,
    -- A stable hash of ClientConfig's own mapping-relevant fields
    -- (currently just dateFormat) -- ClientConfig has no version
    -- number of its own to pin against, the way CanonicalModel does.
    client_config_fingerprint   TEXT NOT NULL,
    -- Sorted, duplicate-preserving multiset of (header, inferredType)
    -- pairs -- see ColumnFingerprint. Column order is deliberately not
    -- part of this: mappings are keyed by name, so a client reordering
    -- columns shouldn't force a fresh agent call.
    column_fingerprint          TEXT NOT NULL,
    -- The final, human-approved MappingProposal (post-amendment if
    -- amended) -- same JSON shape as mapping_proposal.proposal.
    proposal_json                JSONB NOT NULL,
    -- Provenance: which proposal taught us this, kept even after that
    -- proposal's own batch is long since archived.
    source_proposal_id           BIGINT NOT NULL REFERENCES mapping_proposal (id),
    -- ACTIVE / INVALIDATED (a memory-derived proposal using this entry
    -- was later rejected) / CONFLICTED (a second, differently-shaped
    -- approved proposal appeared for the same scope key -- never
    -- silently last-write-wins; see MappingMemoryService). Informal,
    -- same reasoning as every other status column in this schema.
    status                        TEXT NOT NULL DEFAULT 'ACTIVE',
    invalidation_reason           TEXT,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (client_id, worksheet, model_id, model_version, client_config_fingerprint, column_fingerprint)
);

CREATE INDEX idx_mapping_memory_status ON mapping_memory (status);

CREATE TABLE delivery_log (
    id                  BIGSERIAL PRIMARY KEY,
    import_batch_id     BIGINT NOT NULL REFERENCES import_batch (id),
    -- Added after an external review of Step 7 correctly caught that a
    -- batch can have more than one mapping_proposal over its lifetime
    -- (re-approval after a failed delivery, via /redeliver), and the log
    -- didn't record which specific proposal a given delivery attempt
    -- actually came from.
    mapping_proposal_id BIGINT NOT NULL REFERENCES mapping_proposal (id),
    attempt_number      INTEGER NOT NULL,
    transport           TEXT NOT NULL,           -- 'rest' | 'mcp'
    -- SUCCESS / RETRYABLE_FAILURE / TERMINAL_FAILURE / NOT_IMPLEMENTED /
    -- CONFIGURATION_ERROR, per each canonical model's target.delivery
    -- classification (retryableStatusCodes / terminalStatusCodes).
    -- NOT_IMPLEMENTED is for a transport/auth combination Step 7 doesn't
    -- actually dispatch yet (mcp transport, oauth2-client-credentials/
    -- mtls auth). CONFIGURATION_ERROR is for a resolvable-but-wrong
    -- setup (e.g. a missing secret) caught before any network call.
    outcome              TEXT NOT NULL,
    status_code          INTEGER,
    error_message        TEXT,
    attempted_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_log_batch ON delivery_log (import_batch_id);
CREATE INDEX idx_delivery_log_proposal ON delivery_log (mapping_proposal_id);

-- Step 8: durable, row-level validation results. Every prior round
-- (Step 6.1, 7.1, 7.2) flagged this as missing -- ValidationReport
-- previously only ever existed in the HTTP response and application
-- logs, so a reviewer looking at a batch after the fact had no way to
-- see what actually happened during validation, only the batch's
-- current one-word status. One row per validate() call (both /approve
-- and /redeliver trigger one), not one row per canonical field or per
-- source row -- row-level detail lives in row_errors as JSONB, mirroring
-- how mapping_proposal already stores its whole proposal as JSONB
-- rather than exploding it into per-field rows.
CREATE TABLE validation_run (
    id                  BIGSERIAL PRIMARY KEY,
    import_batch_id     BIGINT NOT NULL REFERENCES import_batch (id),
    mapping_proposal_id BIGINT NOT NULL REFERENCES mapping_proposal (id),
    valid_row_count     INTEGER NOT NULL,
    invalid_row_count   INTEGER NOT NULL,
    -- List<ValidationReport.RowError> as JSONB -- [{"rowIndex": 2,
    -- "problems": ["..."]}, ...]. Empty array, not null, when every row
    -- passed.
    row_errors          JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_validation_run_batch ON validation_run (import_batch_id);
CREATE INDEX idx_validation_run_proposal ON validation_run (mapping_proposal_id);

-- Step 9: physical file arrival identity -- deliberately narrower than
-- import_batch's own identity (which also includes worksheet, model,
-- client, and config version: a distinct *unit of reviewable work*,
-- not the same thing as "have I seen these exact bytes under this name
-- before"). Conflating the two was a real design mistake caught before
-- it was built: a plain existence check against import_batch is a
-- check-then-act race across concurrent scanner instances or config
-- reloads (two instances that loaded different config versions could
-- both observe "no batch exists" and both call the model for the same
-- physical file), and "any batch exists for this file" is also too
-- broad on its own -- a batch that hit PROPOSING_ERROR without ever
-- actually producing a proposal would be permanently skipped forever,
-- undermining the exact recovery semantics Step 7.4 built on purpose
-- for that status. A separate table with its own atomic claim
-- (ON CONFLICT DO NOTHING for first discovery, a leased UPDATE for
-- retries -- see InboxFileRepository) closes both gaps.
CREATE TABLE inbox_file (
    id                  BIGSERIAL PRIMARY KEY,
    -- The dedupe identity -- deliberately the bare filename, not a full
    -- path. original_path/current_path change (the file moves once
    -- archived); logical_filename never does, so archiving can never
    -- accidentally change what counts as "the same file arriving
    -- again."
    logical_filename    TEXT NOT NULL,
    content_hash        TEXT NOT NULL,
    original_path       TEXT NOT NULL,
    -- Captured at arrival, from the parsed filename and resolved route
    -- -- needed to build a collision-proof archive destination path
    -- later without re-deriving them (feed_type/source_date in
    -- particular aren't stored anywhere else; import_batch has
    -- client_id but not these).
    feed_type            TEXT,
    client_id            TEXT,
    source_date          DATE,
    worksheet             TEXT,
    -- Same as original_path until archived (Step 9's delivered-only
    -- archiving pass, entirely separate from initial discovery).
    current_path        TEXT NOT NULL,
    -- NEW / PROCESSING / PROPOSAL_CREATED / RETRY_WAIT / QUARANTINED /
    -- ARCHIVED. Informal, same reasoning as import_batch/
    -- mapping_proposal's own status columns above.
    status               TEXT NOT NULL DEFAULT 'NEW',
    import_batch_id      BIGINT REFERENCES import_batch (id),
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    last_error            TEXT,
    -- Set while status = PROCESSING; a scanner instance that crashes
    -- mid-claim leaves a row whose lease simply expires, reclaimable by
    -- the next scan rather than stuck forever. Deliberately automatic
    -- here -- unlike import_batch's own manual-only /recover-stuck
    -- endpoints from Step 7.3 (a human reviewer's own work, recovered
    -- by a human on purpose), inbox_file is a machine-only resource
    -- with no reviewer in the loop to trigger recovery at all, a
    -- genuinely different risk profile.
    lease_until           TIMESTAMPTZ,
    -- Set on a transient (retryable) failure -- when this is null, or
    -- in the past, the row is eligible for a fresh claim attempt.
    next_attempt_at       TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (logical_filename, content_hash)
);

CREATE INDEX idx_inbox_file_status ON inbox_file (status);

-- Local LLM phase, Step LLM-5 (see docs/local-llm-enhancements.md):
-- captures a reviewer's "remember this" signal during proposal review --
-- a field alias or a variant-value mapping worth promoting into a
-- client's durable conventions (ClientModelConventions, Step LLM-3).
-- Deliberately does NOT write to client-configs/*.yaml itself: that
-- file is CanonicalModelRegistry's atomically-reloaded, single-owner
-- config, and round-tripping hand-authored YAML (including its
-- extensive human-written comments) through a generic YAML writer
-- risks silently discarding them -- a real, unresolved risk, not a
-- theoretical one, given how much of client-configs/jpmc.yaml's own
-- content is explanatory comments. This table is the human-reviewable
-- queue between "a reviewer noticed a pattern" and "an administrator
-- deliberately edits the YAML file" -- see this step's build notes in
-- docs/local-llm-enhancements.md for the fuller reasoning and what
-- remains explicitly deferred (Step LLM-5b: an "apply" action that
-- actually writes the YAML).
CREATE TABLE convention_suggestion (
    id                    BIGSERIAL PRIMARY KEY,
    source_proposal_id    BIGINT NOT NULL REFERENCES mapping_proposal (id),
    client_id             TEXT NOT NULL,
    model_id              TEXT NOT NULL,
    -- FIELD_ALIAS (an alternate source column header name for a
    -- canonical field) | VARIANT_VALUE (an observed source value's
    -- mapping to a canonical sum-type variant). Informal, same
    -- reasoning as every other status/kind column in this schema.
    kind                  TEXT NOT NULL,
    canonical_field_path  TEXT NOT NULL,
    -- The alias text (FIELD_ALIAS) or the observed raw source value
    -- (VARIANT_VALUE) -- one column, meaning depends on kind, same
    -- "generic shape, meaning depends on a discriminator" pattern this
    -- project already uses for MappingProposal.FieldMapping's
    -- sourceColumn/sourceConstant pair.
    source_value          TEXT NOT NULL,
    -- Only set for VARIANT_VALUE -- the canonical variant name
    -- source_value should resolve to. Null for FIELD_ALIAS.
    target_variant        TEXT,
    -- PENDING / APPLIED (an administrator folded this into the actual
    -- YAML file -- set manually for now, since Step LLM-5b, the actual
    -- "apply" action, isn't built) / DISMISSED (reviewed and rejected
    -- as not worth remembering).
    status                TEXT NOT NULL DEFAULT 'PENDING',
    suggested_by          TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ
);

CREATE INDEX idx_convention_suggestion_client_status ON convention_suggestion (client_id, status);

-- Two different reviewers (or the same reviewer on two different files)
-- independently noticing the same convention is a confirmation, not a
-- reason to create a second row -- a partial unique index (only over
-- PENDING rows, mirroring uq_mapping_proposal_active_batch's own
-- pattern above) lets ConventionSuggestionRepository upsert cleanly
-- rather than accumulate duplicate suggestions of the same fact.
CREATE UNIQUE INDEX uq_convention_suggestion_pending
    ON convention_suggestion (client_id, model_id, kind, canonical_field_path, source_value)
    WHERE status = 'PENDING';
