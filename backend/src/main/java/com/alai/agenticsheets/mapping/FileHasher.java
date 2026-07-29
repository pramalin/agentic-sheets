package com.alai.agenticsheets.mapping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a SHA-256 hash of a source file's actual bytes, read from the
 * same directory sheets-mcp treats as its own workspace (see
 * {@code compose.yaml}'s {@code workspace-root} mount on both services).
 * Used to dedupe {@code import_batch} rows.
 *
 * Hashed via a buffered stream, not {@code readAllBytes} -- an external
 * review of Step 6 correctly flagged loading a whole workbook into memory
 * as unnecessary for something that only needs to run once over the
 * bytes. Path safety also uses {@code toRealPath()} on both the
 * workspace root and the resolved file, not just string-level
 * {@code normalize()} -- resolving symlinks is what actually closes off
 * a symlink pointing outside the mounted root; normalize() alone only
 * catches {@code ../} showing up literally in the path string.
 */
@Component
public class FileHasher {

    private final Path workspaceRoot;

    public FileHasher(@Value("${agentic-sheets.workspace-root:/workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public String sha256(String relativePath) {
        try {
            Path realRoot = workspaceRoot.toRealPath();
            Path candidate = realRoot.resolve(relativePath).normalize();
            Path realResolved = candidate.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                throw new IllegalArgumentException("Path escapes the workspace root: " + relativePath);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(realResolved);
                    DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (digestIn.read(buffer) != -1) {
                    // DigestInputStream updates the digest as a side effect of read().
                }
            }
            return HexFormat.of().formatHex(digest.digest());
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
