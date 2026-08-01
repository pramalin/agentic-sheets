package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Owns {@code mapping_memory}. See that table's own schema comment for
 * the full design reasoning.
 */
@Repository
public class MappingMemoryRepository {

    private static final String SELECT_COLUMNS =
            "id, client_id, worksheet, model_id, model_version, client_config_fingerprint, "
                    + "column_fingerprint, proposal_json, source_proposal_id, status, invalidation_reason";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public MappingMemoryRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** The one lookup {@link MappingResolutionService} actually needs --
      * an ACTIVE entry for this exact scope key. INVALIDATED and
      * CONFLICTED entries are deliberately excluded, not just
      * deprioritized: both mean "don't trust this without a human
      * looking again," which is exactly what falling through to a real
      * agent call achieves. */
    public Optional<MappingMemory> findActiveMatch(String clientId, String worksheet, String modelId,
            int modelVersion, String clientConfigFingerprint, String columnFingerprint) {
        List<MappingMemory> found = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM mapping_memory "
                        + "WHERE client_id = ? AND worksheet = ? AND model_id = ? AND model_version = ? "
                        + "AND client_config_fingerprint = ? AND column_fingerprint = ? AND status = 'ACTIVE'",
                this::mapRow, clientId, worksheet, modelId, modelVersion, clientConfigFingerprint, columnFingerprint);
        return found.stream().findFirst();
    }

    /**
     * Learns a mapping from a proposal that just cleared clean
     * validation (zero row errors) -- see {@link MappingMemoryService}
     * for the eligibility check this assumes the caller already ran.
     *
     * Conflict-aware, not a plain upsert: if an ACTIVE entry already
     * exists for this exact scope key, compares proposal JSON directly
     * rather than trusting "last write wins." An identical proposal is
     * a no-op confirmation (the existing entry is already correct,
     * nothing to do). A genuinely different proposal for the same key
     * means two different humans (or the same human at different
     * times) approved two different mappings for what this system
     * believes is the same structural situation -- marks the existing
     * entry CONFLICTED and leaves it there for a human to actually
     * look at, rather than silently overwriting one approved judgment
     * with another.
     *
     * @return the memory id that ended up ACTIVE for this scope key, or
     * empty if this call resulted in a conflict (no ACTIVE entry to
     * report)
     */
    public Optional<Long> promote(String clientId, String worksheet, String modelId, int modelVersion,
            String clientConfigFingerprint, String columnFingerprint, MappingProposal proposal,
            long sourceProposalId) {
        Optional<MappingMemory> existing = findActiveMatch(
                clientId, worksheet, modelId, modelVersion, clientConfigFingerprint, columnFingerprint);
        String newJson = jsonMapper.writeValueAsString(proposal);

        if (existing.isPresent()) {
            String existingJson = jsonMapper.writeValueAsString(existing.get().proposal());
            if (existingJson.equals(newJson)) {
                return Optional.of(existing.get().id());
            }
            markConflicted(existing.get().id(),
                    "A different proposal (" + sourceProposalId + ") was approved for the same scope key");
            return Optional.empty();
        }

        long id = jdbcTemplate.queryForObject(
                "INSERT INTO mapping_memory (client_id, worksheet, model_id, model_version, "
                        + "client_config_fingerprint, column_fingerprint, proposal_json, source_proposal_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id",
                Long.class, clientId, worksheet, modelId, modelVersion, clientConfigFingerprint,
                columnFingerprint, newJson, sourceProposalId);
        return Optional.of(id);
    }

    /** A memory-derived proposal that used this entry was later
      * rejected -- disables it so it can't keep producing the same
      * rejected mapping forever. Doesn't delete the row: the audit
      * trail (what did we used to believe, and why did it stop being
      * trusted) is worth keeping. */
    public void invalidate(long id, String reason) {
        jdbcTemplate.update(
                "UPDATE mapping_memory SET status = 'INVALIDATED', invalidation_reason = ?, updated_at = now() "
                        + "WHERE id = ?",
                reason, id);
    }

    private void markConflicted(long id, String reason) {
        jdbcTemplate.update(
                "UPDATE mapping_memory SET status = 'CONFLICTED', invalidation_reason = ?, updated_at = now() "
                        + "WHERE id = ?",
                reason, id);
    }

    public MappingMemory findById(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + SELECT_COLUMNS + " FROM mapping_memory WHERE id = ?",
                this::mapRow, id);
    }

    private MappingMemory mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MappingMemory(
                rs.getLong("id"),
                rs.getString("client_id"),
                rs.getString("worksheet"),
                rs.getString("model_id"),
                rs.getInt("model_version"),
                rs.getString("client_config_fingerprint"),
                rs.getString("column_fingerprint"),
                jsonMapper.readValue(rs.getString("proposal_json"), MappingProposal.class),
                rs.getLong("source_proposal_id"),
                rs.getString("status"),
                rs.getString("invalidation_reason"));
    }
}
