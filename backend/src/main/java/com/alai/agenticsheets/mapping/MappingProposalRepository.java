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
            "id, import_batch_id, config_version, proposal, status, rejection_reason, origin, mapping_memory_id, "
                    + "column_fingerprint, client_config_fingerprint";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public MappingProposalRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** @see #save(long, int, MappingProposal, String, Long, String, String) --
      * this overload defaults origin to AGENT with no memory link or
      * fingerprints, for any caller that hasn't been updated to think
      * about Step 10 provenance explicitly (there are none left in this
      * codebase, but keeping this narrows the blast radius of adding it
      * back for a test or a future caller without immediately needing
      * to plumb fingerprints through). */
    public long save(long importBatchId, int configVersion, MappingProposal proposal) {
        return save(importBatchId, configVersion, proposal, ResolvedProposal.ORIGIN_AGENT, null, null, null);
    }

    public long save(long importBatchId, int configVersion, MappingProposal proposal, String origin,
            Long mappingMemoryId, String columnFingerprint, String clientConfigFingerprint) {
        String json = jsonMapper.writeValueAsString(proposal);
        return jdbcTemplate.queryForObject(
                "INSERT INTO mapping_proposal (import_batch_id, config_version, proposal, origin, "
                        + "mapping_memory_id, column_fingerprint, client_config_fingerprint) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id",
                Long.class, importBatchId, configVersion, json, origin, mappingMemoryId,
                columnFingerprint, clientConfigFingerprint);
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
     * Whether *any* proposal -- regardless of status -- has ever existed
     * for this batch. Deliberately broader than {@link #findPendingByBatchId}:
     * Step 9's scanner uses this specifically, not that, to tell "a human
     * already manually proposed this exact file" (skip -- don't create a
     * competing proposal, whatever its current status) apart from "this
     * batch has genuinely never been proposed" (the only case the
     * scanner should ever call the model for).
     */
    public boolean existsForBatch(long importBatchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mapping_proposal WHERE import_batch_id = ?",
                Integer.class, importBatchId);
        return count != null && count > 0;
    }

    /** The single most recent proposal for a batch, whatever its status
      * -- used alongside {@link #existsForBatch} so Step 9's scanner can
      * link {@code inbox_file} to a proposal a human already created
      * manually, rather than failing when it isn't PENDING. */
    public java.util.Optional<StoredMappingProposal> findMostRecentByBatchId(long importBatchId) {
        List<StoredMappingProposal> found = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM mapping_proposal WHERE import_batch_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                this::mapRow, importBatchId);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    /**
     * The Step 8 review queue, joined with {@code import_batch} for the
     * fields a queue actually needs to be usable -- which client, which
     * file, which worksheet -- rather than the bare IDs {@link
     * StoredMappingProposal} carries. Added once building the actual
     * Step 8b queue view made clear that a list of opaque proposal IDs
     * isn't something a reviewer can do anything with.
     *
     * @param statusFilters narrows to any of the given statuses (an
     * external review correctly pointed out that a single-status filter
     * couldn't express "needs attention" -- PROPOSING_ERROR,
     * VALIDATION_FAILED, PROCESSING_ERROR, DELIVERY_FAILED,
     * SOURCE_CHANGED, and CONFIG_CHANGED are six different statuses,
     * not one); empty or null lists everything, for a broader history
     * view
     */
    public List<ProposalQueueEntry> findQueueEntries(List<String> statusFilters, int limit) {
        String sql = "SELECT p.id, p.import_batch_id, p.status, p.created_at, "
                + "b.model_id, b.client_id, b.source_filename, b.worksheet "
                + "FROM mapping_proposal p JOIN import_batch b ON b.id = p.import_batch_id ";
        if (statusFilters != null && !statusFilters.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(statusFilters.size(), "?"));
            java.util.List<Object> args = new java.util.ArrayList<>(statusFilters);
            args.add(limit);
            return jdbcTemplate.query(
                    sql + "WHERE p.status IN (" + placeholders + ") ORDER BY p.created_at DESC LIMIT ?",
                    this::mapQueueEntryRow, args.toArray());
        }
        return jdbcTemplate.query(
                sql + "ORDER BY p.created_at DESC LIMIT ?",
                this::mapQueueEntryRow, limit);
    }

    private ProposalQueueEntry mapQueueEntryRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProposalQueueEntry(
                rs.getLong("id"),
                rs.getLong("import_batch_id"),
                rs.getString("status"),
                rs.getString("model_id"),
                rs.getString("client_id"),
                rs.getString("source_filename"),
                rs.getString("worksheet"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }

    private StoredMappingProposal mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long mappingMemoryId = rs.getLong("mapping_memory_id");
        boolean mappingMemoryIdWasNull = rs.wasNull();
        return new StoredMappingProposal(
                rs.getLong("id"),
                rs.getLong("import_batch_id"),
                rs.getInt("config_version"),
                jsonMapper.readValue(rs.getString("proposal"), MappingProposal.class),
                rs.getString("status"),
                rs.getString("rejection_reason"),
                rs.getString("origin"),
                mappingMemoryIdWasNull ? null : mappingMemoryId,
                rs.getString("column_fingerprint"),
                rs.getString("client_config_fingerprint"));
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

    /**
     * Same atomic compare-and-set idiom as {@link #claim}, for the third
     * thing that can happen to a pending proposal: a human edits it
     * rather than approving or rejecting outright. The old proposal
     * moves to {@code SUPERSEDED} -- deliberately not {@code REJECTED},
     * since rejection means "this mapping is wrong," while superseding
     * means "this mapping was corrected," a genuinely different fact
     * worth keeping distinct in the audit trail. See
     * {@link ProposalDecisionService#amendProposal} for the transaction
     * that pairs this with inserting the replacement.
     *
     * @return true if this call won the race and the proposal is now
     * SUPERSEDED; false if it wasn't PENDING
     */
    public boolean supersede(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE mapping_proposal SET status = 'SUPERSEDED', reviewed_at = now() "
                        + "WHERE id = ? AND status = 'PENDING'",
                id);
        return updated == 1;
    }
}
