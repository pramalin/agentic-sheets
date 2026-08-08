package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
 * <p>Iterates every map in sorted key order rather than relying on
 * {@code ClientConfig}'s own map iteration order, which isn't
 * guaranteed to be stable ({@code ClientConfigParser} builds its result
 * maps via {@code Map.copyOf}, whose iteration order the JDK explicitly
 * does not promise to preserve) -- two semantically identical configs
 * must always hash the same, regardless of incidental map ordering.
 */
@Component
public class ClientConfigFingerprint {

    public String hash(ClientConfig client) {
        StringBuilder input = new StringBuilder();
        input.append("dateFormat=").append(client.dateFormat());

        client.conventions().keySet().stream().sorted().forEach(modelId -> {
            ClientModelConventions conventions = client.conventions().get(modelId);
            input.append("|model=").append(modelId);
            appendFieldAliases(input, conventions.fieldAliases());
            appendVariantValues(input, conventions.variantValues());
        });

        return sha256(input.toString());
    }

    private void appendFieldAliases(StringBuilder input, Map<String, List<String>> fieldAliases) {
        fieldAliases.keySet().stream().sorted().forEach(path -> {
            input.append("|alias:").append(path).append('=');
            fieldAliases.get(path).stream().sorted().forEach(alias -> input.append(alias).append(','));
        });
    }

    private void appendVariantValues(StringBuilder input, Map<String, Map<String, String>> variantValues) {
        variantValues.keySet().stream().sorted().forEach(path -> {
            input.append("|variant:").append(path).append('=');
            Map<String, String> valueMap = variantValues.get(path);
            valueMap.keySet().stream().sorted()
                    .forEach(sourceValue -> input.append(sourceValue).append("->")
                            .append(valueMap.get(sourceValue)).append(','));
        });
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
