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
}
