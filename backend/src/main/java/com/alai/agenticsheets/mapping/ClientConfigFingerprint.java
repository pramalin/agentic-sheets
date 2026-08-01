package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Step 10: a stable hash of {@link ClientConfig}'s mapping-relevant
 * fields -- currently just {@code dateFormat}, the one field that
 * actually affects mapping/parsing correctness (as distinct from
 * {@code feeds}, which is Step 9 routing metadata with no bearing on
 * how a mapping is interpreted). {@code ClientConfig} has no version
 * number of its own the way {@link com.alai.agenticsheets.canonical.CanonicalModel}
 * does, so a content hash stands in for one -- a client's date
 * convention changing invalidates a remembered mapping's continued
 * safety the same way a model version bump does.
 */
@Component
public class ClientConfigFingerprint {

    public String hash(ClientConfig client) {
        return sha256(client.dateFormat());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
