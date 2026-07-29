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
}
