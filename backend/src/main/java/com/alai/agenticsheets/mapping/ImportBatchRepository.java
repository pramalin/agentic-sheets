package com.alai.agenticsheets.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Owns {@code import_batch}. {@link #findOrCreate} is the same dedupe key
 * Step 9's scheduled inbox scanner will use -- same filename + same
 * content hash reuses the existing batch; a different hash for the same
 * filename means the client corrected the file, so it's a new batch.
 */
@Repository
public class ImportBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public ImportBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long findOrCreate(String modelId, String clientId, String sourceFilename, String contentHash, int configVersion) {
        List<Long> existing = jdbcTemplate.query(
                "SELECT id FROM import_batch WHERE source_filename = ? AND content_hash = ?",
                (rs, rowNum) -> rs.getLong("id"),
                sourceFilename, contentHash);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO import_batch (model_id, client_id, source_filename, content_hash, config_version) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, modelId, clientId, sourceFilename, contentHash, configVersion);
    }
}
