package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A lightweight sanity check run immediately after the agent's structured
 * output is decoded, before persistence. Not a substitute for Step 7's
 * deterministic validator, which will check an <em>approved</em> proposal
 * actually constructs valid canonical rows -- this only catches
 * structurally malformed output (a nonexistent field path, an invented
 * source column, both/neither of a mutually-exclusive pair set, an
 * out-of-range confidence, a variant name that doesn't exist) that Spring
 * AI's structured-output binding doesn't enforce on its own, since the
 * response schema comes from the fixed {@code MappingProposal} Java
 * record, not from the runtime canonical ADT the agent was shown as
 * prompt text. See {@code mapping-notes.md}'s "Step 6.1 hardening"
 * section for why this exists as a separate check rather than trying to
 * make the LLM call itself schema-constrained by the ADT.
 */
@Component
public class MappingProposalStructuralValidator {

    public List<String> validate(MappingProposal proposal, CanonicalModel model, Set<String> observedColumns) {
        CanonicalPaths paths = CanonicalPaths.of(model);
        List<String> problems = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        // fieldMappings can never be null here -- MappingProposal's own
        // compact constructor normalizes that -- but empty is a real,
        // reachable state (a genuinely malformed or truncated model
        // response, per Step LLM-6's real Qwen 2.5 3B finding -- see
        // docs/local-llm-enhancements.md) that the per-entry loop below
        // would otherwise silently accept: an empty list has nothing to
        // iterate, so it would report zero problems and pass structural
        // validation clean, persisting a proposal that maps nothing with
        // no signal to the reviewer about why. Flagged explicitly, once,
        // here, rather than left to surface confusingly later (every row
        // failing every field) at /approve time.
        if (proposal.fieldMappings().isEmpty()) {
            problems.add("the proposal contains no field mappings at all -- likely malformed or "
                    + "truncated model output, not a legitimate empty mapping");
        }

        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
            if (fm == null) {
                // A null list element, not just a null or empty list --
                // the real Step LLM-6 schema-echo finding (see
                // docs/local-llm-enhancements.md) proved malformed
                // structured output isn't theoretical. MappingProposal's
                // compact constructor normalizes the list reference
                // itself, but can't sanitize individual elements without
                // silently discarding a signal that something is
                // genuinely wrong -- reported here, explicitly, instead.
                problems.add("a fieldMapping entry is null -- likely malformed or truncated model output");
                continue;
            }
            String path = fm.canonicalFieldPath();

            if (path == null || path.isBlank()) {
                problems.add("a fieldMapping has a blank canonicalFieldPath");
                continue;
            }
            if (!seenPaths.add(path)) {
                problems.add("canonicalFieldPath '" + path + "' appears more than once");
            }
            if (!paths.isValidPath(path)) {
                problems.add("canonicalFieldPath '" + path + "' is not a field in " + model.modelId());
                continue;
            }

            boolean hasColumn = isSet(fm.sourceColumn());
            boolean hasConstant = isSet(fm.sourceConstant());
            if (hasColumn && hasConstant) {
                problems.add("'" + path + "' sets both sourceColumn and sourceConstant");
            }
            if (hasColumn && !observedColumns.contains(fm.sourceColumn())) {
                problems.add("'" + path + "' references sourceColumn '" + fm.sourceColumn()
                        + "', which was not in the observed table");
            }

            if (fm.confidence() < 0.0 || fm.confidence() > 1.0) {
                problems.add("'" + path + "' has confidence " + fm.confidence() + ", outside 0.0-1.0");
            }

            boolean hasSelectedVariant = isSet(fm.selectedVariant());
            boolean hasVariantMap = fm.variantValueMap() != null && !fm.variantValueMap().isEmpty();
            if (hasSelectedVariant && hasVariantMap) {
                problems.add("'" + path + "' sets both selectedVariant and variantValueMap");
            }
            if (hasVariantMap && !hasColumn) {
                problems.add("'" + path + "' has a variantValueMap but no sourceColumn to read each row's "
                        + "value from -- variantValueMap always needs a discriminator column");
            }
            if (hasSelectedVariant || hasVariantMap) {
                if (!paths.isSumTypePath(path)) {
                    problems.add("'" + path + "' sets a variant but is not a sum type field");
                } else {
                    Set<String> validVariants = paths.variantsAt(path);
                    if (hasSelectedVariant && !validVariants.contains(fm.selectedVariant())) {
                        problems.add("'" + path + "' selectedVariant '" + fm.selectedVariant()
                                + "' is not one of " + validVariants);
                    }
                    if (hasVariantMap) {
                        for (String variant : fm.variantValueMap().values()) {
                            if (!validVariants.contains(variant)) {
                                problems.add("'" + path + "' variantValueMap maps to '" + variant
                                        + "', not one of " + validVariants);
                            }
                        }
                    }
                }
            } else if (paths.isSumTypePath(path)) {
                // A mapping entry exists for this sum type field, but it
                // resolves neither way -- unlike a primitive field, this
                // is never a legitimate "genuinely unavailable" signal:
                // omitting the mapping entirely is how a sum type field
                // says "no data for this," the same as any other field.
                // Proposing an entry that can't actually be resolved is
                // always a malformed proposal.
                problems.add("'" + path + "' is a sum type field with a mapping entry but neither "
                        + "selectedVariant nor variantValueMap set");
            }

            if (fm.transformations() != null) {
                for (MappingProposal.TransformationStep step : fm.transformations()) {
                    if (!"scale".equals(step.type())) {
                        problems.add("'" + path + "' proposes an unrecognized transformation type '"
                                + step.type() + "' -- only 'scale' is currently implemented");
                        continue;
                    }
                    if (paths.primitiveKindAt(path) != com.alai.agenticsheets.canonical.PrimitiveType.Kind.NUMBER) {
                        problems.add("'" + path + "' proposes a 'scale' transformation, but only NUMBER "
                                + "fields support one");
                        continue;
                    }
                    try {
                        new java.math.BigDecimal(step.multiplier() == null ? "" : step.multiplier().trim());
                    } catch (NumberFormatException e) {
                        problems.add("'" + path + "' has a 'scale' transformation with an unparseable "
                                + "multiplier '" + step.multiplier() + "'");
                    }
                }
            }
        }
        return problems;
    }

    private boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * A second, deliberately separate invariant from {@link #validate}:
     * every *observed* source column must be accounted for -- either
     * mapped by some entry's {@code sourceColumn}, or explicitly listed
     * in {@code unmappedSourceColumns}. {@link #validate} checks that
     * each *individual* entry is well-formed; this checks that the
     * *proposal as a whole* is complete, a genuinely different
     * question -- following an external review that found a real,
     * severe gap this distinction closes.
     *
     * <p>Step LLM-4's field-alias merge (see
     * {@code docs/local-llm-enhancements.md}) combines
     * deterministically-resolved {@code fieldMappings} with whatever the
     * model itself returned. If the model call failed or returned
     * nothing usable, the merged proposal's {@code unmappedSourceColumns}
     * still reflected only the *empty synthesized fallback* proposal,
     * not the real observed table -- silently dropping any column the
     * model was supposed to handle but never got the chance to, with no
     * signal anything was ever wrong. Step LLM-6's whole point was that
     * a malformed/failed model result fails loudly; merging
     * deterministic results back in had quietly defeated that, since a
     * mostly-complete (if silently one column short) proposal never
     * trips {@link #validate}'s own "no field mappings at all" check --
     * it isn't empty, it's just missing exactly the one column that
     * actually needed the model.
     *
     * <p>Deliberately a separate method, not folded into {@code validate}'s
     * existing per-entry loop: this project's existing test suite
     * almost universally tests one field mapping at a time against the
     * real JPMC column set, without expecting every *other* column to
     * be independently accounted for in the same assertion -- folding
     * this check into {@code validate} directly would have broken
     * essentially every existing test in this file for a reason
     * unrelated to what each of them actually tests. Kept separate so
     * every one of those tests stays exactly as focused and unchanged
     * as it already was; this method has its own, purpose-built tests
     * instead.
     */
    public List<String> validateColumnCoverage(MappingProposal proposal, Set<String> observedColumns) {
        List<String> problems = new ArrayList<>();

        Set<String> mappedColumns = new java.util.LinkedHashSet<>();
        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
            if (fm != null && isSet(fm.sourceColumn())) {
                mappedColumns.add(fm.sourceColumn());
            }
        }
        Set<String> unmapped = new java.util.LinkedHashSet<>(proposal.unmappedSourceColumns());

        Set<String> invented = new java.util.LinkedHashSet<>(unmapped);
        invented.removeAll(observedColumns);
        if (!invented.isEmpty()) {
            problems.add("unmappedSourceColumns lists " + invented + ", which "
                    + (invented.size() == 1 ? "was" : "were") + " never actually observed in the source table");
        }

        Set<String> contradictory = new java.util.LinkedHashSet<>(mappedColumns);
        contradictory.retainAll(unmapped);
        if (!contradictory.isEmpty()) {
            problems.add("column(s) " + contradictory + " are both mapped by a fieldMapping and listed in "
                    + "unmappedSourceColumns -- contradictory");
        }

        Set<String> accountedFor = new java.util.LinkedHashSet<>(mappedColumns);
        accountedFor.addAll(unmapped);
        Set<String> missing = new java.util.LinkedHashSet<>(observedColumns);
        missing.removeAll(accountedFor);
        if (!missing.isEmpty()) {
            problems.add("observed source column(s) " + missing + " are neither mapped by any fieldMapping "
                    + "nor listed in unmappedSourceColumns -- silently unaccounted for");
        }

        return problems;
    }
}
