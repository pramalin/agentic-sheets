package com.alai.agenticsheets.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owns {@code inbox_file} -- see that table's schema comment for why its
 * identity is deliberately narrower than {@code import_batch}'s own.
 *
 * Two-step claim, matching {@link com.alai.agenticsheets.mapping.ImportBatchRepository
 * #claimForProcessing}'s own established idiom exactly ({@code update()}
 * returning a boolean success flag, not a {@code RETURNING} clause): a
 * plain, idempotent {@link #recordArrival} first (so a file already
 * known is a safe no-op), then a separate, eligibility-gated
 * {@link #claimForProcessing} that only one caller can ever win for a
 * given attempt.
 */
@Repository
public class InboxFileRepository {

    private final JdbcTemplate jdbcTemplate;

    public InboxFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records that this exact (filename, hash) has been observed at
     * least once. Idempotent -- a no-op if already known, deliberately:
     * this alone does not grant processing rights, so calling it
     * repeatedly (every scan cycle, for every file still sitting in the
     * inbox) is always safe. feedType/clientId/sourceDate/worksheet are
     * only ever written on the *first* insert (the ON CONFLICT clause
     * doesn't touch them) -- they describe how this file was routed
     * when the scanner first found it, not something a later scan
     * should overwrite.
     */
    public void recordArrival(String logicalFilename, String contentHash, String originalPath,
            String feedType, String clientId, java.time.LocalDate sourceDate, String worksheet) {
        jdbcTemplate.update(
                "INSERT INTO inbox_file (logical_filename, content_hash, original_path, current_path, "
                        + "feed_type, client_id, source_date, worksheet) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (logical_filename, content_hash) DO NOTHING",
                logicalFilename, contentHash, originalPath, originalPath,
                feedType, clientId, sourceDate, worksheet);
    }

    /**
     * Atomically claims this file for a processing attempt -- its first
     * ({@code NEW}), a retry whose delay has elapsed
     * ({@code RETRY_WAIT} with {@code next_attempt_at <= now()}), or one
     * whose previous claim's lease expired without ever completing (a
     * scanner instance that crashed mid-attempt). {@code maxAttempts} is
     * enforced directly in this same {@code WHERE} clause, not checked
     * separately afterward -- a check-then-act gap here would recreate
     * the exact class of race this table exists to avoid.
     *
     * @return true if this call won the claim; false if the row wasn't
     * in an eligible state (already claimed by a concurrent attempt,
     * quarantined, already produced a proposal, or exhausted its retry
     * budget)
     */
    public boolean claimForProcessing(
            String logicalFilename, String contentHash, int maxAttempts, Duration leaseDuration) {
        int updated = jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'PROCESSING', attempt_count = attempt_count + 1, "
                        + "lease_until = now() + (? || ' seconds')::interval, updated_at = now() "
                        + "WHERE logical_filename = ? AND content_hash = ? AND attempt_count < ? "
                        + "AND (status = 'NEW' "
                        + "OR (status = 'RETRY_WAIT' AND next_attempt_at <= now()) "
                        + "OR (status = 'PROCESSING' AND lease_until < now()))",
                leaseDuration.toSeconds(), logicalFilename, contentHash, maxAttempts);
        return updated == 1;
    }

    public Optional<InboxFile> findByLogicalFilenameAndHash(String logicalFilename, String contentHash) {
        List<InboxFile> found = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM inbox_file WHERE logical_filename = ? AND content_hash = ?",
                InboxFileRepository::mapRow, logicalFilename, contentHash);
        return found.stream().findFirst();
    }

    /** A successful initial proposal -- the only outcome that ends this
      * row's involvement with retry/quarantine handling entirely. */
    public void markProposalCreated(long id, long importBatchId) {
        jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'PROPOSAL_CREATED', import_batch_id = ?, "
                        + "last_error = NULL, updated_at = now() WHERE id = ?",
                importBatchId, id);
    }

    /** A transient failure -- eligible for another attempt once
      * {@code nextAttemptAt} passes, up to {@code claimForProcessing}'s
      * own {@code maxAttempts} bound. */
    public void markRetryWait(long id, String errorMessage, Instant nextAttemptAt) {
        jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'RETRY_WAIT', last_error = ?, next_attempt_at = ?, "
                        + "updated_at = now() WHERE id = ?",
                errorMessage, nextAttemptAt, id);
    }

    /** A permanent, non-retryable problem (unroutable filename, unknown
      * client/feed, unsupported file type) -- deliberately terminal, not
      * retried on the next scan the way a transient failure is. */
    public void markQuarantined(long id, String reason) {
        jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'QUARANTINED', last_error = ?, updated_at = now() WHERE id = ?",
                reason, id);
    }

    /**
     * Every {@code PROPOSAL_CREATED} row whose linked batch has reached
     * {@code DELIVERED} and hasn't been archived yet -- the read side of
     * Step 9's separate, delivered-only archiving pass (never triggered
     * by proposing or approving; see that pass's own class javadoc for
     * why archiving any earlier would break Step 7's source-drift
     * check).
     */
    public List<InboxFile> findDeliveredAwaitingArchive() {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM inbox_file f "
                        + "JOIN import_batch b ON b.id = f.import_batch_id "
                        + "WHERE f.status = 'PROPOSAL_CREATED' AND b.status = 'DELIVERED'",
                InboxFileRepository::mapRow);
    }

    /** Atomic: only one caller can ever archive a given row -- the same
      * compare-and-set idiom as every other claim in this project, not
      * a plain unconditional update. */
    public boolean claimForArchiving(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'ARCHIVING', updated_at = now() "
                        + "WHERE id = ? AND status = 'PROPOSAL_CREATED'",
                id);
        return updated == 1;
    }

    public void markArchived(long id, String newPath) {
        jdbcTemplate.update(
                "UPDATE inbox_file SET status = 'ARCHIVED', current_path = ?, archived_at = now(), "
                        + "updated_at = now() WHERE id = ?",
                newPath, id);
    }

    private static final String SELECT_COLUMNS =
            "id, logical_filename, content_hash, original_path, current_path, feed_type, client_id, "
                    + "source_date, worksheet, status, import_batch_id, attempt_count, last_error";

    private static InboxFile mapRow(ResultSet rs, int rowNum) throws SQLException {
        long importBatchId = rs.getLong("import_batch_id");
        // Checked immediately, not after the several other column reads
        // below -- wasNull() reflects whichever getter was called most
        // recently, not necessarily this one.
        boolean importBatchIdWasNull = rs.wasNull();
        java.sql.Date sourceDate = rs.getDate("source_date");
        return new InboxFile(
                rs.getLong("id"),
                rs.getString("logical_filename"),
                rs.getString("content_hash"),
                rs.getString("original_path"),
                rs.getString("current_path"),
                rs.getString("feed_type"),
                rs.getString("client_id"),
                sourceDate == null ? null : sourceDate.toLocalDate(),
                rs.getString("worksheet"),
                rs.getString("status"),
                importBatchIdWasNull ? null : importBatchId,
                rs.getInt("attempt_count"),
                rs.getString("last_error"));
    }
}
