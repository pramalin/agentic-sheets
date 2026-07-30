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
    -- Informal for now (PENDING / MAPPED / APPROVED / REJECTED /
    -- PROCESSING / VALIDATION_FAILED / PROCESSING_ERROR /
    -- SOURCE_CHANGED / CONFIG_CHANGED / DELIVERED / DELIVERY_FAILED),
    -- not a DB-level enum or CHECK
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
    -- PENDING / APPROVED / REJECTED, informal for the same reasons as
    -- import_batch.status above.
    status              TEXT NOT NULL DEFAULT 'PENDING',
    reviewed_by         TEXT,
    reviewed_at         TIMESTAMPTZ,
    -- Step 8: a human's stated reason for rejecting -- null for an
    -- approval, or for a proposal nobody has reviewed yet.
    rejection_reason    TEXT,
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

CREATE TABLE mapping_memory (
    id                      BIGSERIAL PRIMARY KEY,
    model_id                TEXT NOT NULL,
    client_id               TEXT NOT NULL,
    -- Part of the cache key, not just metadata: bumping a team's config
    -- version naturally invalidates their old cached mappings instead of
    -- silently reusing one built against fields that no longer exist or
    -- mean something different now.
    config_version          INTEGER NOT NULL,
    column_fingerprint      TEXT NOT NULL,
    mapping                 JSONB NOT NULL,
    approved_from_batch_id  BIGINT NOT NULL REFERENCES import_batch (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, client_id, config_version, column_fingerprint)
);

CREATE INDEX idx_mapping_memory_lookup
    ON mapping_memory (model_id, client_id, config_version, column_fingerprint);

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
