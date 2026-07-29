package com.alai.agenticsheets.mapping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a SHA-256 hash of a source file's actual bytes, read from the
 * same directory sheets-mcp treats as its own workspace (see
 * {@code compose.yaml}'s {@code workspace-root} mount on both services).
 * Used to dedupe {@code import_batch} rows: same filename + same hash
 * means already processed; same filename + a different hash means the
 * client corrected the file. Step 9's scheduled inbox scanner will use
 * this exact same logic; this manual-trigger flow (Step 6) exercises it
 * early.
 */
@Component
public class FileHasher {

    private final Path workspaceRoot;

    public FileHasher(@Value("${agentic-sheets.workspace-root:/workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public String sha256(String relativePath) {
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            // Same path-traversal guard reasoning as sheets-reader-mcp's
            // own WorkspacePathResolver -- a relative path shouldn't be
            // able to escape the mounted workspace directory.
            throw new IllegalArgumentException("Path escapes the workspace root: " + relativePath);
        }
        try {
            byte[] bytes = Files.readAllBytes(resolved);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read file for hashing: " + relativePath, e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM -- this
            // is unreachable in practice, but MessageDigest.getInstance
            // declares the checked exception regardless.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
