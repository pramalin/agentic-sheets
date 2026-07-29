package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns {@code mapping_proposal}. Insert-only for Step 6 -- a proposal
 * lands as {@code PENDING} and stays there; approve/reject and the
 * review UI arrive in Steps 7-8. {@code config_version} is pinned at
 * creation time (see {@code CanonicalModel}'s own javadoc) so a later
 * config change can't retroactively affect a proposal already awaiting
 * review.
 */
@Repository
public class MappingProposalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public MappingProposalRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public long save(long importBatchId, int configVersion, MappingProposal proposal) {
        String json = jsonMapper.writeValueAsString(proposal);
        return jdbcTemplate.queryForObject(
                "INSERT INTO mapping_proposal (import_batch_id, config_version, proposal) "
                        + "VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, importBatchId, configVersion, json);
    }

    public StoredMappingProposal findById(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, import_batch_id, config_version, proposal, status FROM mapping_proposal WHERE id = ?",
                (rs, rowNum) -> new StoredMappingProposal(
                        rs.getLong("id"),
                        rs.getLong("import_batch_id"),
                        rs.getInt("config_version"),
                        jsonMapper.readValue(rs.getString("proposal"), MappingProposal.class),
                        rs.getString("status")),
                id);
    }

    /**
     * Atomically claims a pending proposal for approval -- {@code WHERE
     * status = 'PENDING'} makes this a compare-and-set, not a
     * check-then-act. An external review correctly caught that the
     * original {@code updateStatus} was unconditional, called only after
     * a separate {@code findById} + status check in application code --
     * two concurrent {@code /approve} requests could both pass that
     * check before either write landed, both proceed to validate and
     * dispatch, and deliver the same batch twice. The affected-row-count
     * this returns is the actual race protection: if it's zero, someone
     * else (or something else) already claimed it, or it was never
     * PENDING to begin with.
     *
     * @return true if this call won the race and the proposal is now
     * APPROVED; false if it wasn't PENDING (already claimed, already
     * approved, or never existed in that state)
     */
    public boolean claim(long id, String reviewedBy) {
        int updated = jdbcTemplate.update(
                "UPDATE mapping_proposal SET status = 'APPROVED', reviewed_by = ?, reviewed_at = now() "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewedBy, id);
        return updated == 1;
    }

    public void updateStatus(long id, String status, String reviewedBy) {
        jdbcTemplate.update(
                "UPDATE mapping_proposal SET status = ?, reviewed_by = ?, reviewed_at = now() WHERE id = ?",
                status, reviewedBy, id);
    }
}
