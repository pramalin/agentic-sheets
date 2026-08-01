package com.alai.agenticsheets.mapping;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Step 10: a structural fingerprint of a {@code describe_table} result
 * -- the sorted, duplicate-preserving multiset of
 * {@code (header, inferredType)} pairs, hashed. Two files with the same
 * fingerprint have the same column structure, in the one sense that
 * matters for mapping reuse: every {@code sourceColumn} reference in a
 * remembered {@link MappingProposal} would resolve to a real column of
 * the same inferred type.
 *
 * Column <em>order</em> is deliberately not part of this -- mappings
 * are keyed by column name, not position, so a client reordering
 * columns between files shouldn't force a fresh agent call. Column
 * <em>uniqueness</em> is deliberately not assumed -- a plain
 * {@code Set} would silently collapse two identically-named,
 * identically-typed duplicate columns into one entry; sorting a
 * {@code List} instead preserves that duplication in the fingerprint,
 * so a workbook with a genuine duplicate-header quirk is fingerprinted
 * honestly rather than pretending it looks like a single-column file.
 */
@Component
public class ColumnFingerprint {

    public record ColumnSignature(String header, String inferredType) {
    }

    public List<ColumnSignature> extract(JsonNode table) {
        List<ColumnSignature> signatures = new ArrayList<>();
        JsonNode columns = table.get("columns");
        if (columns != null && columns.isArray()) {
            for (JsonNode col : columns) {
                JsonNode headerNode = col.get("header");
                JsonNode typeNode = col.get("inferredType");
                if (headerNode != null) {
                    String header = headerNode.asText();
                    String inferredType = typeNode != null ? typeNode.asText() : "";
                    signatures.add(new ColumnSignature(header, inferredType));
                }
            }
        }
        return signatures;
    }

    /** A stable, sortable, human-inspectable canonical form -- computed
      * once and reused both for hashing and for any future debugging
      * ("why didn't this match?") without needing to reconstruct it. */
    public String canonicalForm(List<ColumnSignature> signatures) {
        return signatures.stream()
                .sorted(Comparator.comparing(ColumnSignature::header).thenComparing(ColumnSignature::inferredType))
                .map(s -> s.header() + ":" + s.inferredType())
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    public String hash(JsonNode table) {
        return sha256(canonicalForm(extract(table)));
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm -- unreachable in
            // practice, same reasoning FileHasher already documents for
            // its own identical catch.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
