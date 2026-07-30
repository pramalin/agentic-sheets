package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns {@code delivery_log} -- one row per delivery attempt (not per
  * canonical row; a whole batch's valid rows dispatch as a single call,
  * see {@link Dispatcher}). */
@Repository
public class DeliveryLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(long importBatchId, long mappingProposalId, int attemptNumber, String transport, String outcome,
            Integer statusCode, String errorMessage) {
        jdbcTemplate.update(
                "INSERT INTO delivery_log (import_batch_id, mapping_proposal_id, attempt_number, transport, outcome, status_code, error_message) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                importBatchId, mappingProposalId, attemptNumber, transport, outcome, statusCode, errorMessage);
    }

    public java.util.List<DeliveryLogEntry> findByBatchId(long importBatchId) {
        return jdbcTemplate.query(
                "SELECT id, import_batch_id, mapping_proposal_id, attempt_number, transport, outcome, "
                        + "status_code, error_message, attempted_at FROM delivery_log "
                        + "WHERE import_batch_id = ? ORDER BY attempted_at ASC",
                (rs, rowNum) -> new DeliveryLogEntry(
                        rs.getLong("id"),
                        rs.getLong("import_batch_id"),
                        rs.getLong("mapping_proposal_id"),
                        rs.getInt("attempt_number"),
                        rs.getString("transport"),
                        rs.getString("outcome"),
                        (Integer) rs.getObject("status_code"),
                        rs.getString("error_message"),
                        rs.getObject("attempted_at", java.time.OffsetDateTime.class)),
                importBatchId);
    }

    /**
     * Scoped to one proposal, not the whole batch -- an external review
     * correctly caught that {@code GET /proposals/{id}}, which calls
     * itself one proposal's full detail, was using
     * {@link #findByBatchId} for its delivery history. Since a batch can
     * legitimately have several historical proposals (reject, re-propose,
     * approve a different one), that meant proposal B's detail view could
     * show delivery attempts that actually came from proposal A.
     */
    public java.util.List<DeliveryLogEntry> findByProposalId(long mappingProposalId) {
        return jdbcTemplate.query(
                "SELECT id, import_batch_id, mapping_proposal_id, attempt_number, transport, outcome, "
                        + "status_code, error_message, attempted_at FROM delivery_log "
                        + "WHERE mapping_proposal_id = ? ORDER BY attempted_at ASC",
                (rs, rowNum) -> new DeliveryLogEntry(
                        rs.getLong("id"),
                        rs.getLong("import_batch_id"),
                        rs.getLong("mapping_proposal_id"),
                        rs.getInt("attempt_number"),
                        rs.getString("transport"),
                        rs.getString("outcome"),
                        (Integer) rs.getObject("status_code"),
                        rs.getString("error_message"),
                        rs.getObject("attempted_at", java.time.OffsetDateTime.class)),
                mappingProposalId);
    }
}
