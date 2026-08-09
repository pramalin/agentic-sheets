package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Step 10: a stable hash of {@link ClientConfig}'s mapping-relevant
 * fields -- {@code dateFormat}, and, as of the Local LLM phase's Step
 * LLM-3 (see {@code docs/local-llm-enhancements.md}), {@code conventions}
 * too. {@code ClientConfig} has no version number of its own the way
 * {@link com.alai.agenticsheets.canonical.CanonicalModel} does, so a
 * content hash stands in for one -- a client's date convention, or now a
 * client's field-alias/variant-value vocabulary, changing invalidates a
 * remembered mapping's continued safety the same way a model version
 * bump does. {@code feeds} is deliberately excluded, same as before --
 * that's Step 9 routing metadata with no bearing on how a mapping is
 * interpreted.
 *
 * <p>Serializes a canonical, fully-sorted structure to real JSON (via
 * {@link JsonMapper}, the same library {@link MappingProposalRepository}
 * already uses for JSONB persistence) and hashes that, rather than
 * concatenating values with hand-picked delimiters. Following an
 * external review after Step LLM-6: the original delimiter-based
 * approach had a genuine, verified hash collision -- alias lists
 * {@code ["a,b", "c"]} and {@code ["a", "b,c"]}, both sorted, both
 * concatenated to the identical string {@code "a,b,c,"}, meaning two
 * semantically different configurations produced the same fingerprint,
 * defeating the entire invalidation guarantee this class exists to
 * provide. Escaping the chosen delimiters more carefully would have
 * fixed that one case but not the general problem -- real JSON
 * serialization already handles arbitrary string content correctly (a
 * comma inside a JSON string is quoted, not a structural delimiter),
 * which is a stronger guarantee than trying to anticipate and escape
 * every character a client's own alias or variant-value text might
 * someday contain.
 *
 * <p>Every map is a {@link TreeMap} -- sorted by construction, not
 * relying on {@code ClientConfig}'s own map iteration order, which isn't
 * guaranteed to be stable ({@code ClientConfigParser} builds its result
 * maps via {@code Map.copyOf}, whose iteration order the JDK explicitly
 * does not promise to preserve) -- two semantically identical configs
 * must always hash the same, regardless of incidental map ordering.
 */
@Component
public class ClientConfigFingerprint {

    private final JsonMapper jsonMapper;

    public ClientConfigFingerprint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String hash(ClientConfig client) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("dateFormat", client.dateFormat());

        Map<String, Object> conventionsCanonical = new TreeMap<>();
        for (Map.Entry<String, ClientModelConventions> entry : client.conventions().entrySet()) {
            conventionsCanonical.put(entry.getKey(), canonicalize(entry.getValue()));
        }
        canonical.put("conventions", conventionsCanonical);

        String json = jsonMapper.writeValueAsString(canonical);
        return sha256(json);
    }

    private Map<String, Object> canonicalize(ClientModelConventions conventions) {
        Map<String, Object> result = new TreeMap<>();

        Map<String, List<String>> sortedAliases = new TreeMap<>();
        conventions.fieldAliases().forEach(
                (path, aliases) -> sortedAliases.put(path, aliases.stream().sorted().toList()));
        result.put("fieldAliases", sortedAliases);

        Map<String, Map<String, String>> sortedVariantValues = new TreeMap<>();
        conventions.variantValues().forEach(
                (path, valueMap) -> sortedVariantValues.put(path, new TreeMap<>(valueMap)));
        result.put("variantValues", sortedVariantValues);

        return result;
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
