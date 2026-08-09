package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Owns {@code convention_suggestion}. See that table's own schema
 * comment for the full design reasoning. Local LLM phase, Step LLM-5
 * (see {@code docs/local-llm-enhancements.md}).
 */
@Repository
public class ConventionSuggestionRepository {

    private static final String SELECT_COLUMNS =
            "id, source_proposal_id, client_id, model_id, kind, canonical_field_path, source_value, "
                    + "target_variant, status, suggested_by, created_at, resolved_at";

    private final JdbcTemplate jdbcTemplate;

    public ConventionSuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a new PENDING suggestion, or -- if an identical PENDING
     * suggestion already exists for this exact {@code (clientId, modelId,
     * kind, canonicalFieldPath, sourceValue)} -- returns the existing one
     * unchanged, provided its target agrees. Race-safe via
     * {@code ON CONFLICT ... DO NOTHING RETURNING id} against
     * {@code uq_convention_suggestion_pending}, the same atomic-upsert
     * idiom {@code ImportBatchRepository} already uses for exactly this
     * reason (a plain check-then-insert would race two concurrent
     * callers exactly the way this project's own Step 6.1/7.3 hardening
     * rounds found and fixed for other tables).
     *
     * <p>Following an external review: the unique index deliberately
     * doesn't include {@code target_variant} (see that index's own
     * schema comment for why -- two reviewers independently suggesting
     * the *same* fact should confirm, not duplicate). But that meant a
     * genuinely *different* target for the same source value -- e.g.
     * {@code USD -> USD} already pending, then someone suggests
     * {@code USD -> EUR} -- hit the identical conflict target and
     * silently returned the *first* row, with no signal that a
     * different fact had been proposed and discarded. The conflict
     * target and the *target* agreement are now two separate questions:
     * a same-target conflict is still treated as confirmation (idempotent,
     * returns the existing row); a different-target conflict is a real
     * disagreement worth surfacing, not silently resolved by whichever
     * suggestion happened to arrive first.
     *
     * @throws IllegalStateException if a PENDING suggestion for the same
     * {@code (clientId, modelId, kind, canonicalFieldPath, sourceValue)}
     * already exists with a genuinely different {@code targetVariant} --
     * mapped to an HTTP 409 by {@code MappingController}'s existing
     * {@code IllegalStateException} handler, the same status every other
     * "already in a conflicting state" condition in this project uses.
     */
    public ConventionSuggestion suggest(long sourceProposalId, String clientId, String modelId, String kind,
            String canonicalFieldPath, String sourceValue, String targetVariant, String suggestedBy) {
        List<Long> inserted = jdbcTemplate.query(
                "INSERT INTO convention_suggestion (source_proposal_id, client_id, model_id, kind, "
                        + "canonical_field_path, source_value, target_variant, suggested_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (client_id, model_id, kind, canonical_field_path, source_value) "
                        + "WHERE status = 'PENDING' DO NOTHING "
                        + "RETURNING id",
                (rs, rowNum) -> rs.getLong("id"),
                sourceProposalId, clientId, modelId, kind, canonicalFieldPath, sourceValue, targetVariant,
                suggestedBy);

        if (!inserted.isEmpty()) {
            return findById(inserted.get(0));
        }

        ConventionSuggestion existing = findPending(clientId, modelId, kind, canonicalFieldPath, sourceValue)
                .orElseThrow(() -> new IllegalStateException(
                        "insert conflicted but no matching PENDING suggestion was found -- unexpected"));

        if (!java.util.Objects.equals(existing.targetVariant(), targetVariant)) {
            throw new IllegalStateException("a conflicting PENDING convention suggestion already exists for "
                    + clientId + "/" + modelId + "/" + kind + "/" + canonicalFieldPath + "/" + sourceValue
                    + " -- existing target is '" + existing.targetVariant() + "', new suggestion proposes '"
                    + targetVariant + "'. Dismiss the existing suggestion first if the new one is correct.");
        }

        return existing;
    }

    public Optional<ConventionSuggestion> findPending(String clientId, String modelId, String kind,
            String canonicalFieldPath, String sourceValue) {
        List<ConventionSuggestion> found = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM convention_suggestion WHERE client_id = ? AND model_id = ? "
                        + "AND kind = ? AND canonical_field_path = ? AND source_value = ? AND status = 'PENDING'",
                this::mapRow, clientId, modelId, kind, canonicalFieldPath, sourceValue);
        return found.stream().findFirst();
    }

    public List<ConventionSuggestion> findByClientAndStatus(String clientId, String status) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM convention_suggestion WHERE client_id = ? AND status = ? "
                        + "ORDER BY created_at DESC",
                this::mapRow, clientId, status);
    }

    public ConventionSuggestion findById(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + SELECT_COLUMNS + " FROM convention_suggestion WHERE id = ?", this::mapRow, id);
    }

    /** @throws IllegalStateException if {@code id} isn't currently PENDING -- dismissing
      * an already-resolved suggestion (applied or already dismissed) isn't a legitimate action. */
    public void dismiss(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE convention_suggestion SET status = 'DISMISSED', resolved_at = now() "
                        + "WHERE id = ? AND status = 'PENDING'",
                id);
        if (updated == 0) {
            throw new IllegalStateException("suggestion " + id + " is not PENDING -- cannot dismiss");
        }
    }

    private ConventionSuggestion mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConventionSuggestion(
                rs.getLong("id"),
                rs.getLong("source_proposal_id"),
                rs.getString("client_id"),
                rs.getString("model_id"),
                rs.getString("kind"),
                rs.getString("canonical_field_path"),
                rs.getString("source_value"),
                rs.getString("target_variant"),
                rs.getString("status"),
                rs.getString("suggested_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class));
    }
}
