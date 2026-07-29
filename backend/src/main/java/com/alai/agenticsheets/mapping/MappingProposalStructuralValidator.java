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

        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
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
}
