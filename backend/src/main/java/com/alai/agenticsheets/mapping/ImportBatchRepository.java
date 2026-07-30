package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns {@code import_batch}. {@link #findOrCreate} is the same dedupe key
 * Step 9's scheduled inbox scanner will use.
 *
 * A single atomic upsert, not select-then-insert: an external review of
 * Step 6 correctly caught that the original select-then-insert let two
 * concurrent requests for the same batch race each other, with one
 * failing on the unique constraint instead of both cleanly resolving to
 * the same row. {@code ON CONFLICT ... DO UPDATE} is the standard
 * Postgres idiom for a no-op-ish update that still lets
 * {@code RETURNING id} report the existing row on conflict --
 * {@code DO NOTHING} alone doesn't return anything when it hits the
 * conflict path.
 */
@Repository
public class ImportBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public ImportBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long findOrCreate(String modelId, String clientId, String sourceFilename, String contentHash,
            String worksheet, int configVersion) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO import_batch (model_id, client_id, source_filename, content_hash, worksheet, config_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_filename, content_hash, worksheet, model_id, client_id, config_version) "
                        + "DO UPDATE SET model_id = EXCLUDED.model_id "
                        + "RETURNING id",
                Long.class, modelId, clientId, sourceFilename, contentHash, worksheet, configVersion);
    }

    public ImportBatch findById(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, model_id, client_id, source_filename, content_hash, worksheet, config_version, status "
                        + "FROM import_batch WHERE id = ?",
                (rs, rowNum) -> new ImportBatch(
                        rs.getLong("id"),
                        rs.getString("model_id"),
                        rs.getString("client_id"),
                        rs.getString("source_filename"),
                        rs.getString("content_hash"),
                        rs.getString("worksheet"),
                        rs.getInt("config_version"),
                        rs.getString("status")),
                id);
    }

    public void updateStatus(long id, String status) {
        jdbcTemplate.update("UPDATE import_batch SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    /**
     * Conditional compare-and-set: only overwrites the status if it's
     * still exactly {@code expectedCurrentStatus}. Added after a
     * fourth-round external review caught that the generic
     * catch-and-mark-PROCESSING_ERROR handler in
     * {@code MappingController.processDelivery} was unconditionally
     * overwriting a more specific status (SOURCE_CHANGED, CONFIG_CHANGED)
     * that had already been recorded moments earlier, in the same call,
     * right before the exception that triggered the catch block. Using
     * this instead of a plain {@link #updateStatus} in that catch block
     * means the catch only ever "downgrades" a batch that's still
     * genuinely {@code PROCESSING} -- something more specific already
     * recorded stays recorded.
     *
     * @return true if the update actually happened (status was indeed
     * {@code expectedCurrentStatus}); false if it had already moved on
     * to something else
     */
    public boolean updateStatusIfCurrent(long id, String expectedCurrentStatus, String newStatus) {
        int updated = jdbcTemplate.update(
                "UPDATE import_batch SET status = ?, updated_at = now() WHERE id = ? AND status = ?",
                newStatus, id, expectedCurrentStatus);
        return updated == 1;
    }

    /**
     * Atomically claims a batch for delivery processing -- {@code WHERE
     * status IN (...)} makes this a compare-and-set, the same idiom as
     * {@link MappingProposalRepository#claim}, applied to the piece that
     * one wasn't protecting. An external review correctly caught that
     * the atomic proposal claim only protected the one-time
     * PENDING->APPROVED transition; nothing stopped two concurrent
     * {@code /redeliver} calls (or a {@code /redeliver} racing the
     * original {@code /approve} request's own in-flight delivery) from
     * both reaching {@code Dispatcher.dispatch} for the same batch,
     * since {@code /redeliver} only checked the *proposal's* status
     * (permanently {@code APPROVED} once approved), never the batch's.
     *
     * @param eligibleFromStatuses the batch statuses this call is
     * allowed to claim from -- different for {@code /approve} (fresh
     * off proposal approval, so just {@code PENDING}) than for
     * {@code /redeliver} (retrying after a recorded failure, so
     * {@code APPROVED}/{@code DELIVERY_FAILED}/{@code PROCESSING_ERROR},
     * deliberately excluding {@code DELIVERED} -- redelivering something
     * already successfully delivered is exactly the duplicate-delivery
     * risk this method exists to prevent)
     * @return true if this call won the claim and the batch is now
     * {@code PROCESSING}; false if it wasn't in an eligible state --
     * already being processed by a concurrent request, already
     * delivered, or something else entirely
     */
    public boolean claimForProcessing(long id, java.util.Set<String> eligibleFromStatuses) {
        java.util.List<String> statuses = java.util.List.copyOf(eligibleFromStatuses);
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        args.addAll(statuses);
        int updated = jdbcTemplate.update(
                "UPDATE import_batch SET status = 'PROCESSING', updated_at = now() "
                        + "WHERE id = ? AND status IN (" + placeholders + ")",
                args.toArray());
        return updated == 1;
    }
}
