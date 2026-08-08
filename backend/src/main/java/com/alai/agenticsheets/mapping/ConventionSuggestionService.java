package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Validates and records a reviewer's "remember this" signal during
 * proposal review -- Local LLM phase, Step LLM-5 (see
 * {@code docs/local-llm-enhancements.md}). Deliberately validates against
 * the actual canonical model at suggestion time, the earliest point a
 * mistake can be caught, rather than storing an unvalidated suggestion
 * and discovering later (whenever an administrator eventually tries to
 * apply it) that it references a field or variant that doesn't exist --
 * the same "verify at the earliest useful point" discipline
 * {@link com.alai.agenticsheets.canonical.ClientConventionsValidator}
 * already applies to conventions actually loaded into
 * {@code client-configs/*.yaml}.
 */
@Service
public class ConventionSuggestionService {

    private final MappingProposalRepository mappingProposalRepository;
    private final ImportBatchRepository importBatchRepository;
    private final CanonicalModelRegistry registry;
    private final ConventionSuggestionRepository suggestionRepository;

    public ConventionSuggestionService(
            MappingProposalRepository mappingProposalRepository,
            ImportBatchRepository importBatchRepository,
            CanonicalModelRegistry registry,
            ConventionSuggestionRepository suggestionRepository) {
        this.mappingProposalRepository = mappingProposalRepository;
        this.importBatchRepository = importBatchRepository;
        this.registry = registry;
        this.suggestionRepository = suggestionRepository;
    }

    /**
     * @param kind {@link ConventionSuggestion#KIND_FIELD_ALIAS} or
     * {@link ConventionSuggestion#KIND_VARIANT_VALUE}
     * @param canonicalFieldPath must be a real field path in the
     * proposal's canonical model
     * @param sourceValue the alias text ({@code FIELD_ALIAS}) or the
     * observed raw source value ({@code VARIANT_VALUE})
     * @param targetVariant required and must be a real variant of
     * {@code canonicalFieldPath} for {@code VARIANT_VALUE}; must be
     * absent for {@code FIELD_ALIAS}
     * @throws IllegalArgumentException if the suggestion doesn't
     * validate against the actual canonical model
     */
    public ConventionSuggestion suggest(long proposalId, String kind, String canonicalFieldPath,
            String sourceValue, String targetVariant, String suggestedBy) {
        StoredMappingProposal stored = mappingProposalRepository.findById(proposalId);
        ImportBatch batch = importBatchRepository.findById(stored.importBatchId());
        CanonicalModel model = registry.get(batch.modelId());
        CanonicalPaths paths = CanonicalPaths.of(model);

        if (canonicalFieldPath == null || canonicalFieldPath.isBlank()) {
            throw new IllegalArgumentException("canonicalFieldPath must not be blank");
        }
        if (sourceValue == null || sourceValue.isBlank()) {
            throw new IllegalArgumentException("sourceValue must not be blank");
        }
        if (!paths.isValidPath(canonicalFieldPath)) {
            throw new IllegalArgumentException(
                    "'" + canonicalFieldPath + "' is not a field in " + model.modelId());
        }

        switch (kind) {
            case ConventionSuggestion.KIND_FIELD_ALIAS -> {
                if (targetVariant != null) {
                    throw new IllegalArgumentException("a " + ConventionSuggestion.KIND_FIELD_ALIAS
                            + " suggestion must not set targetVariant");
                }
            }
            case ConventionSuggestion.KIND_VARIANT_VALUE -> {
                if (!paths.isSumTypePath(canonicalFieldPath)) {
                    throw new IllegalArgumentException("'" + canonicalFieldPath + "' is not a sum type field -- "
                            + ConventionSuggestion.KIND_VARIANT_VALUE + " suggestions only apply to sum types");
                }
                if (targetVariant == null || targetVariant.isBlank()) {
                    throw new IllegalArgumentException(
                            "a " + ConventionSuggestion.KIND_VARIANT_VALUE + " suggestion needs a targetVariant");
                }
                Set<String> validVariants = paths.variantsAt(canonicalFieldPath);
                if (!validVariants.contains(targetVariant)) {
                    throw new IllegalArgumentException("'" + targetVariant + "' is not a valid variant of '"
                            + canonicalFieldPath + "' (" + validVariants + ")");
                }
            }
            default -> throw new IllegalArgumentException("unknown suggestion kind '" + kind + "' -- must be "
                    + ConventionSuggestion.KIND_FIELD_ALIAS + " or " + ConventionSuggestion.KIND_VARIANT_VALUE);
        }

        return suggestionRepository.suggest(proposalId, batch.clientId(), batch.modelId(), kind,
                canonicalFieldPath, sourceValue, targetVariant, suggestedBy);
    }
}
