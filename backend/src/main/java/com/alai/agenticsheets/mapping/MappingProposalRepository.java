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

    public void updateStatus(long id, String status, String reviewedBy) {
        jdbcTemplate.update(
                "UPDATE mapping_proposal SET status = ?, reviewed_by = ?, reviewed_at = now() WHERE id = ?",
                status, reviewedBy, id);
    }
}
