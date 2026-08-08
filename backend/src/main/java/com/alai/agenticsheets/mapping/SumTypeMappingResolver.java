package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Deterministically resolves and validates sum type variant metadata on a
 * {@link MappingProposal}, using the full set of observed source rows
 * (via {@link SpreadsheetRowReader}) rather than {@code describe_table}'s
 * sample values. Local LLM phase, Step LLM-2 -- see
 * {@code docs/local-llm-enhancements.md} for the motivation: local-model
 * testing found that even a 32B model reliably left this exact class of
 * field unresolved or wrong, even though the correct answer is already
 * derivable from data the application reads anyway.
 *
 * <p>Deliberately has no dependency on client configuration -- this is
 * canonical-name matching only (does an observed raw value correspond,
 * exactly or after light normalization, to one of the canonical model's
 * own variant names). Client-specific vocabulary (Step LLM-3, e.g. "JPMC's
 * 'Fixed Income' means canonical variant FixedIncome") is a separate,
 * higher-priority lookup that Step LLM-4 will consult ahead of this one --
 * this resolver stays the correct, narrower fallback for values a client
 * hasn't configured, or for a client with no configuration at all.
 *
 * <p>Conservative by design, matching this project's established stance on
 * deterministic code (see {@code MappingMemoryEligibility}'s similar
 * conservatism): every rule here either fills in something uniquely
 * derivable, or flags a conflict -- it never guesses, and it never repairs
 * a structurally contradictory proposal ({@code selectedVariant} and
 * {@code variantValueMap} both set). Non-sum-type field mappings are
 * never touched.
 */
@Component
public class SumTypeMappingResolver {

    private final SpreadsheetRowReader rowReader;

    public SumTypeMappingResolver(SpreadsheetRowReader rowReader) {
        this.rowReader = rowReader;
    }

    public record Result(MappingProposal proposal, List<MappingResolutionProblem> problems) {
    }

    /**
     * Resolves {@code proposal} against {@code model}'s ADT and the full
     * observed rows of {@code sourcePath}/{@code worksheet}. Reads the
     * source rows at most once per call, and only if the proposal actually
     * has a sum-type field mapping to examine.
     */
    public Result resolve(MappingProposal proposal, CanonicalModel model, String sourcePath, String worksheet) {
        CanonicalPaths paths = CanonicalPaths.of(model);
        List<MappingResolutionProblem> problems = new ArrayList<>();

        boolean hasSumTypeMapping = proposal.fieldMappings().stream()
                .anyMatch(fm -> paths.isSumTypePath(fm.canonicalFieldPath()));
        List<Map<String, String>> rows = hasSumTypeMapping
                ? rowReader.readAll(sourcePath, worksheet)
                : List.of();

        List<MappingProposal.FieldMapping> resolvedMappings = new ArrayList<>();
        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
            String path = fm.canonicalFieldPath();
            if (path == null || !paths.isSumTypePath(path)) {
                resolvedMappings.add(fm);
                continue;
            }
            resolvedMappings.add(resolveOne(path, fm, paths.variantsAt(path), rows, problems));
        }

        MappingProposal resolvedProposal = new MappingProposal(
                resolvedMappings, proposal.unmappedSourceColumns(), proposal.summary());
        return new Result(resolvedProposal, problems);
    }

    private MappingProposal.FieldMapping resolveOne(String path, MappingProposal.FieldMapping fm,
            Set<String> validVariants, List<Map<String, String>> rows, List<MappingResolutionProblem> problems) {

        boolean hasSelected = isSet(fm.selectedVariant());
        boolean hasMap = fm.variantValueMap() != null && !fm.variantValueMap().isEmpty();

        // Structurally contradictory (both set) -- never repair; leave it
        // exactly as proposed so MappingProposalStructuralValidator
        // continues to reject it, same as before this resolver existed.
        if (hasSelected && hasMap) {
            return fm;
        }
        if (hasSelected) {
            return validateSelectedVariant(path, fm, validVariants, rows, problems);
        }
        if (hasMap) {
            return validateVariantValueMap(path, fm, validVariants, rows, problems);
        }
        return fillUnresolved(path, fm, validVariants, rows, problems);
    }

    /**
     * The core enrichment case: a sum type field mapping exists (e.g.
     * {@code currency} with {@code sourceColumn: "Currency"}) but the
     * agent left both {@code selectedVariant} and {@code variantValueMap}
     * unset -- exactly the empty-{@code selectedVariant} gap
     * {@code mapping-notes.md}'s Step 6 build notes first identified, and
     * the class of field the 3B/7B/14B/32B benchmark in
     * {@code docs/local-llm-evaluation.md} found local models kept
     * stumbling on.
     */
    private MappingProposal.FieldMapping fillUnresolved(String path, MappingProposal.FieldMapping fm,
            Set<String> validVariants, List<Map<String, String>> rows, List<MappingResolutionProblem> problems) {

        if (!isSet(fm.sourceColumn())) {
            problems.add(new MappingResolutionProblem(MappingResolutionProblem.Kind.UNRESOLVED, path,
                    fm.sourceColumn(),
                    "'" + path + "' is an unresolved sum type field with no sourceColumn to observe values "
                            + "from -- cannot derive a variant deterministically", true));
            return fm;
        }

        Set<String> distinctValues = distinctNonBlankValues(fm.sourceColumn(), rows);
        if (distinctValues.isEmpty()) {
            problems.add(new MappingResolutionProblem(MappingResolutionProblem.Kind.UNRESOLVED, path,
                    fm.sourceColumn(),
                    "'" + path + "' sourceColumn '" + fm.sourceColumn() + "' has no non-blank observed "
                            + "values -- nothing to derive a variant from", true));
            return fm;
        }

        Map<String, String> resolvedByValue = new LinkedHashMap<>();
        List<String> unresolvedValues = new ArrayList<>();
        for (String value : distinctValues) {
            matchVariant(value, validVariants).ifPresentOrElse(
                    variant -> resolvedByValue.put(value, variant),
                    () -> unresolvedValues.add(value));
        }

        if (!unresolvedValues.isEmpty()) {
            problems.add(new MappingResolutionProblem(MappingResolutionProblem.Kind.UNRESOLVED, path,
                    fm.sourceColumn(),
                    "'" + path + "' observed value(s) " + unresolvedValues + " in column '" + fm.sourceColumn()
                            + "' do not uniquely resolve to any of " + validVariants
                            + " -- leaving unresolved rather than guessing", true));
            return fm;
        }

        Set<String> distinctResolvedVariants = new LinkedHashSet<>(resolvedByValue.values());
        if (distinctResolvedVariants.size() == 1) {
            return withSelectedVariant(fm, distinctResolvedVariants.iterator().next());
        }
        return withVariantValueMap(fm, resolvedByValue);
    }

    /**
     * Validates an agent-supplied {@code selectedVariant} against every
     * distinct observed value in its {@code sourceColumn}, if it has one --
     * this is exactly the check that would have caught the 3B benchmark's
     * {@code selectedVariant=Equity} proposal against a file whose
     * {@code Asset Class} column actually contained both {@code Equity}
     * and {@code Fixed Income} rows. If there's no {@code sourceColumn} at
     * all, there's nothing to cross-check -- {@code CanonicalRowBuilder}
     * already applies a column-less {@code selectedVariant} unconditionally
     * to every row, and the resolver has no additional signal to add over
     * that.
     */
    private MappingProposal.FieldMapping validateSelectedVariant(String path, MappingProposal.FieldMapping fm,
            Set<String> validVariants, List<Map<String, String>> rows, List<MappingResolutionProblem> problems) {

        if (!isSet(fm.sourceColumn())) {
            return fm;
        }

        Set<String> distinctValues = distinctNonBlankValues(fm.sourceColumn(), rows);
        List<String> conflicting = new ArrayList<>();
        for (String value : distinctValues) {
            Optional<String> matched = matchVariant(value, validVariants);
            if (matched.isEmpty() || !matched.get().equals(fm.selectedVariant())) {
                conflicting.add(value);
            }
        }

        if (!conflicting.isEmpty()) {
            problems.add(new MappingResolutionProblem(MappingResolutionProblem.Kind.SEMANTIC_CONFLICT, path,
                    fm.sourceColumn(),
                    "'" + path + "' proposes selectedVariant '" + fm.selectedVariant() + "', but column '"
                            + fm.sourceColumn() + "' also contains " + conflicting
                            + ", which " + (conflicting.size() == 1 ? "does" : "do")
                            + " not resolve to that same variant", true));
        }
        // Preserve unchanged either way -- valid metadata is never
        // regenerated, and a conflict is reported, never silently repaired.
        return fm;
    }

    /**
     * Validates an agent-supplied {@code variantValueMap} covers every
     * distinct observed value in its (required) {@code sourceColumn}.
     * {@code variantValueMap} without a {@code sourceColumn} is already
     * structurally invalid (caught by {@link MappingProposalStructuralValidator});
     * nothing for this resolver to add in that case.
     */
    private MappingProposal.FieldMapping validateVariantValueMap(String path, MappingProposal.FieldMapping fm,
            Set<String> validVariants, List<Map<String, String>> rows, List<MappingResolutionProblem> problems) {

        if (!isSet(fm.sourceColumn())) {
            return fm;
        }

        Set<String> distinctValues = distinctNonBlankValues(fm.sourceColumn(), rows);
        List<String> uncovered = new ArrayList<>();
        for (String value : distinctValues) {
            if (!fm.variantValueMap().containsKey(value)) {
                uncovered.add(value);
            }
        }

        if (!uncovered.isEmpty()) {
            problems.add(new MappingResolutionProblem(MappingResolutionProblem.Kind.SEMANTIC_CONFLICT, path,
                    fm.sourceColumn(),
                    "'" + path + "' variantValueMap does not cover observed value(s) " + uncovered
                            + " in column '" + fm.sourceColumn() + "'", true));
        }
        return fm;
    }

    private MappingProposal.FieldMapping withSelectedVariant(MappingProposal.FieldMapping fm, String variant) {
        return new MappingProposal.FieldMapping(fm.canonicalFieldPath(), fm.sourceColumn(), fm.sourceConstant(),
                variant, null, fm.transformations(), fm.confidence(), fm.conversionNotes());
    }

    private MappingProposal.FieldMapping withVariantValueMap(MappingProposal.FieldMapping fm,
            Map<String, String> variantValueMap) {
        return new MappingProposal.FieldMapping(fm.canonicalFieldPath(), fm.sourceColumn(), fm.sourceConstant(),
                null, variantValueMap, fm.transformations(), fm.confidence(), fm.conversionNotes());
    }

    private Set<String> distinctNonBlankValues(String sourceColumn, List<Map<String, String>> rows) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            String value = row.get(sourceColumn);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Deterministic matching only, in three tiers from most to least
     * strict -- no edit distance, no embeddings, no LLM call, no synonyms
     * (those are for canonical *field* names, a different concern; see
     * {@code CanonicalModel.synonyms}). A match is accepted only if
     * exactly one canonical variant matches at the first tier that
     * produces any match at all:
     * <ol>
     *   <li>exact, after trimming whitespace (so a trailing-space cell
     *       value like {@code "USD "} still matches {@code "USD"} without
     *       being treated the same as a genuinely different token);</li>
     *   <li>case-insensitive;</li>
     *   <li>normalized -- lowercased, with whitespace, {@code _}, and
     *       {@code -} stripped (so {@code "Fixed Income"} and
     *       {@code "fixed-income"} both resolve to {@code FixedIncome}).</li>
     * </ol>
     * A tier that matches more than one variant is ambiguous and is not
     * treated as a match -- resolution falls through to the next tier (if
     * any), and if no tier produces a unique match, the value is left
     * unresolved rather than guessed.
     */
    private Optional<String> matchVariant(String rawValue, Set<String> validVariants) {
        String trimmed = rawValue.trim();

        Optional<String> exact = uniqueMatch(validVariants, v -> v.equals(trimmed));
        if (exact.isPresent()) {
            return exact;
        }
        Optional<String> caseInsensitive = uniqueMatch(validVariants, v -> v.equalsIgnoreCase(trimmed));
        if (caseInsensitive.isPresent()) {
            return caseInsensitive;
        }
        String normalizedValue = normalize(trimmed);
        return uniqueMatch(validVariants, v -> normalize(v).equals(normalizedValue));
    }

    private Optional<String> uniqueMatch(Set<String> candidates, Predicate<String> test) {
        List<String> matches = candidates.stream().filter(test).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    private boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
