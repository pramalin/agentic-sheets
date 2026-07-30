package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Owns {@code mapping_proposal}. {@code config_version} is pinned at
 * creation time (see {@code CanonicalModel}'s own javadoc) so a later
 * config change can't retroactively affect a proposal already awaiting
 * review.
 */
@Repository
public class MappingProposalRepository {

    private static final String SELECT_COLUMNS =
            "id, import_batch_id, config_version, proposal, status, rejection_reason";

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
                "SELECT " + SELECT_COLUMNS + " FROM mapping_proposal WHERE id = ?",
                this::mapRow, id);
    }

    /**
     * The existing PENDING proposal for a batch, if any -- backs the
     * "at most one active proposal per batch" policy in
     * {@code MappingController.propose}. An external review correctly
     * caught that {@code /propose} previously inserted a new proposal
     * unconditionally on every call, with no check for an existing one
     * -- calling it twice against the same batch created two PENDING
     * proposals racing each other for a single batch only one of them
     * could ever actually be delivered through.
     */
    public java.util.Optional<StoredMappingProposal> findPendingByBatchId(long importBatchId) {
        List<StoredMappingProposal> found = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM mapping_proposal WHERE import_batch_id = ? AND status = 'PENDING'",
                this::mapRow, importBatchId);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    /**
     * Lists proposals, most recent first -- the Step 8 review queue.
     * {@code statusFilter} narrows to one status (typically
     * {@code "PENDING"}, for "what needs review right now"); null lists
     * everything, for a broader history view.
     */
    public List<StoredMappingProposal> findAll(String statusFilter, int limit) {
        if (statusFilter != null) {
            return jdbcTemplate.query(
                    "SELECT " + SELECT_COLUMNS + " FROM mapping_proposal WHERE status = ? "
                            + "ORDER BY created_at DESC LIMIT ?",
                    this::mapRow, statusFilter, limit);
        }
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM mapping_proposal ORDER BY created_at DESC LIMIT ?",
                this::mapRow, limit);
    }

    private StoredMappingProposal mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StoredMappingProposal(
                rs.getLong("id"),
                rs.getLong("import_batch_id"),
                rs.getInt("config_version"),
                jsonMapper.readValue(rs.getString("proposal"), MappingProposal.class),
                rs.getString("status"),
                rs.getString("rejection_reason"));
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

    /**
     * Same atomic compare-and-set idiom as {@link #claim}, for the
     * other terminal decision a human can make about a pending proposal.
     *
     * @return true if this call won the race and the proposal is now
     * REJECTED; false if it wasn't PENDING
     */
    public boolean reject(long id, String reviewedBy, String reason) {
        int updated = jdbcTemplate.update(
                "UPDATE mapping_proposal SET status = 'REJECTED', reviewed_by = ?, reviewed_at = now(), "
                        + "rejection_reason = ? WHERE id = ? AND status = 'PENDING'",
                reviewedBy, reason, id);
        return updated == 1;
    }
}
