package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically matches observed source column headers to canonical
 * field paths, before the LLM ever sees the file -- Local LLM phase,
 * Step LLM-4's originally-deferred piece, finally built following an
 * external review's Finding 6 (see {@code docs/local-llm-enhancements.md}):
 * without this, Step LLM-6's benchmark still asked the model to
 * reconstruct the *entire* column-to-field mapping, with only sum-type
 * variant mechanics actually moved out of its hands -- not the
 * "known headers -> deterministic, known vocabulary -> deterministic,
 * one unresolved column -> LLM" architecture this phase was ultimately
 * aiming for.
 *
 * <p>Two sources of deterministic naming knowledge: a canonical field's
 * own name, and {@link ClientModelConventions#fieldAliases()}
 * (client-specific, Step LLM-3, explicitly human-approved -- e.g. one
 * client's own {@code "Ccy"} for {@code currency}). All candidate names
 * from both, plus the field's own path, are merged into one flat,
 * normalized lookup; an observed column matches a field only if its
 * normalized form is claimed by exactly that one field. A column that
 * matches nothing, or that collides ambiguously between two different
 * fields' candidates, is deliberately left unresolved for the LLM --
 * consistent with every other deterministic resolver in this phase,
 * this class never guesses.
 *
 * <p>{@link CanonicalModel#synonyms()} is deliberately NOT one of these
 * sources, despite an earlier version of this class treating it as
 * one -- corrected following a second external review round. This
 * project's own {@code canonical-models/SCHEMA.md} already documented
 * synonyms as "additional context" for the mapping agent (an LLM
 * prompt hint), not an approved deterministic equivalence -- and that
 * documented intent predates this resolver. Treating synonyms as
 * deterministic was a real, unilateral policy shift this class made
 * without updating that documentation or getting explicit sign-off,
 * and the review's own example makes the risk concrete: Holdings'
 * real {@code synonyms:} block includes entries like
 * {@code security_description -> name} and {@code market_value -> value}
 * -- safe as a *hint* the LLM weighs alongside everything else it can
 * see, but not obviously safe to trust blindly at confidence 1.0 on a
 * different file where a generic column literally called "Name" or
 * "Value" might mean something else entirely. The correct fix was to
 * bring the code back in line with the pre-existing documented intent,
 * not to unilaterally decide the more aggressive interpretation should
 * become the new standard and rewrite the docs to match. Promoting
 * specific, vetted synonyms to deterministic status -- the same way a
 * client's own {@code fieldAliases} already work, human-approved rather
 * than auto-trusted -- remains a real, separate, future design decision
 * for whoever owns a given canonical model, not something this
 * resolver should decide on its own by treating every synonym as
 * equally trustworthy regardless of how generic or specific it is.
 *
 * <p>Only ever resolves top-level (non-dot) field paths -- a sum type's
 * own path (e.g. {@code asset_class}) gets its *column* matched here,
 * exactly like a primitive field, but its *variant value* is a separate
 * question {@link SumTypeMappingResolver} already owns; a deeper,
 * variant-qualified sub-field path (e.g.
 * {@code asset_class.FixedIncome.maturity_date}) is out of scope for
 * this resolver. {@code client_id} (and any path ending in
 * {@code .client_id}) is never a candidate here at all -- it's resolved
 * externally, before either this resolver or the LLM is ever involved,
 * per the same rule the system prompt itself already states.
 */
@Component
public class FieldAliasResolver {

    public record Result(
            List<MappingProposal.FieldMapping> resolvedMappings,
            Set<String> resolvedSourceColumns) {
    }

    public Result resolve(CanonicalModel model, ClientConfig client, Set<String> observedColumns) {
        CanonicalPaths paths = CanonicalPaths.of(model);
        ClientModelConventions conventions = client.conventions().get(model.modelId());
        Map<String, List<String>> configuredAliases =
                conventions != null ? conventions.fieldAliases() : Map.of();

        Map<String, String> normalizedToPath = buildCandidateIndex(paths, configuredAliases);

        List<MappingProposal.FieldMapping> resolved = new ArrayList<>();
        Set<String> consumedColumns = new LinkedHashSet<>();
        for (String column : observedColumns) {
            String matchedPath = normalizedToPath.get(normalize(column));
            if (matchedPath == null) {
                continue;
            }
            resolved.add(new MappingProposal.FieldMapping(matchedPath, column, null, null, null, null, 1.0,
                    "deterministically resolved from the canonical field's own name or a configured "
                            + "client alias, not proposed by the model"));
            consumedColumns.add(column);
        }

        return new Result(resolved, consumedColumns);
    }

    /**
     * Builds one flat {@code normalized candidate name -> canonical
     * field path} index from both sources at once (each field's own
     * name, configured aliases), so ambiguity detection considers them
     * together. A collision between two *different* fields' candidates
     * removes that normalized key entirely -- fails closed, deferring
     * to the LLM, rather than guessing which field actually owns it.
     */
    private Map<String, String> buildCandidateIndex(CanonicalPaths paths, Map<String, List<String>> configuredAliases) {
        Map<String, String> normalizedToPath = new LinkedHashMap<>();
        Set<String> ambiguousNormalized = new LinkedHashSet<>();

        for (String path : paths.allPaths()) {
            if (isTopLevel(path) && !isClientId(path)) {
                claim(normalizedToPath, ambiguousNormalized, path, path);
                for (String alias : configuredAliases.getOrDefault(path, List.of())) {
                    claim(normalizedToPath, ambiguousNormalized, path, alias);
                }
            }
        }

        ambiguousNormalized.forEach(normalizedToPath::remove);
        return normalizedToPath;
    }

    private void claim(Map<String, String> normalizedToPath, Set<String> ambiguousNormalized,
            String path, String candidateName) {
        String normalized = normalize(candidateName);
        String owner = normalizedToPath.putIfAbsent(normalized, path);
        if (owner != null && !owner.equals(path)) {
            ambiguousNormalized.add(normalized);
        }
    }

    private boolean isTopLevel(String path) {
        return !path.contains(".");
    }

    private boolean isClientId(String path) {
        return path.equals("client_id") || path.endsWith(".client_id");
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }
}
