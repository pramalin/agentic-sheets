package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Owns {@code validation_run} -- durable, row-level validation results.
 * Flagged repeatedly across Step 7.1/7.2's external reviews as missing:
 * {@link ValidationReport} previously only ever existed in the HTTP
 * response and application logs, so a reviewer looking at a batch after
 * the fact had no way to see what actually happened during validation,
 * only the batch's current one-word status.
 */
@Repository
public class ValidationRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public ValidationRunRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public long save(long importBatchId, long mappingProposalId, ValidationReport report) {
        String rowErrorsJson = jsonMapper.writeValueAsString(report.rowErrors());
        return jdbcTemplate.queryForObject(
                "INSERT INTO validation_run (import_batch_id, mapping_proposal_id, valid_row_count, "
                        + "invalid_row_count, row_errors) VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
                Long.class, importBatchId, mappingProposalId, report.validRows().size(),
                report.rowErrors().size(), rowErrorsJson);
    }

    public List<ValidationRun> findByProposalId(long proposalId) {
        JavaType rowErrorListType = jsonMapper.getTypeFactory()
                .constructCollectionType(List.class, ValidationReport.RowError.class);
        return jdbcTemplate.query(
                "SELECT id, import_batch_id, mapping_proposal_id, valid_row_count, invalid_row_count, "
                        + "row_errors, created_at FROM validation_run WHERE mapping_proposal_id = ? "
                        + "ORDER BY created_at DESC",
                (rs, rowNum) -> new ValidationRun(
                        rs.getLong("id"),
                        rs.getLong("import_batch_id"),
                        rs.getLong("mapping_proposal_id"),
                        rs.getInt("valid_row_count"),
                        rs.getInt("invalid_row_count"),
                        jsonMapper.readValue(rs.getString("row_errors"), rowErrorListType),
                        rs.getObject("created_at", OffsetDateTime.class)),
                proposalId);
    }
}
