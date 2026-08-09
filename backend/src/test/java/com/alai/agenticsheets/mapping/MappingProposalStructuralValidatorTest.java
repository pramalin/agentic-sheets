package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MappingProposalStructuralValidatorTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();
    private final MappingProposalStructuralValidator validator = new MappingProposalStructuralValidator();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private static final Set<String> JPMC_COLUMNS = Set.of(
            "Account", "As Of Date", "CUSIP", "Description", "Asset Class",
            "Quantity", "Unit Cost", "Price", "Market Value", "Currency", "Custodian");

    @Test
    void acceptsAWellFormedProposal() throws Exception {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        fm("as_of_date", "As Of Date", null, null, null, 0.95),
                        fm("asset_class", "Asset Class", null, null,
                                Map.of("Equity", "Equity", "Fixed Income", "FixedIncome"), 0.9),
                        fm("currency", "Currency", null, "USD", null, 0.98)),
                List.of(),
                "looks fine");

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).isEmpty();
    }

    @Test
    void rejectsANonexistentCanonicalFieldPath() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("not_a_real_field", "Account", null, null, null, 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("not_a_real_field") && p.contains("not a field"));
    }

    @Test
    void rejectsAnInventedSourceColumn() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("account_id", "This Column Does Not Exist", null, null, null, 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("not in the observed table"));
    }

    @Test
    void rejectsBothSourceColumnAndSourceConstantSetTogether() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("account_id", "Account", "some-constant", null, null, 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("both sourceColumn and sourceConstant"));
    }

    @Test
    void rejectsConfidenceOutsideZeroToOne() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("account_id", "Account", null, null, null, 1.5));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("outside 0.0-1.0"));
    }

    @Test
    void rejectsBothSelectedVariantAndVariantValueMapSetTogether() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("asset_class", "Asset Class", null, "Equity", Map.of("Cash", "Cash"), 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("both selectedVariant and variantValueMap"));
    }

    @Test
    void rejectsAVariantNameThatDoesNotExistOnThatSumType() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("asset_class", "Asset Class", null, "NotARealVariant", null, 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("NotARealVariant") && p.contains("not one of"));
    }

    @Test
    void rejectsAVariantValueMapPointingAtAnInvalidVariant() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("asset_class", "Asset Class", null, null, Map.of("Equity", "NotARealVariant"), 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("NotARealVariant"));
    }

    @Test
    void rejectsAVariantSetOnAFieldThatIsNotASumType() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("account_id", "Account", null, "Equity", null, 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("not a sum type field"));
    }

    @Test
    void rejectsADuplicateCanonicalFieldPath() throws Exception {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        fm("account_id", "Account", null, null, null, 0.9),
                        fm("account_id", "Account", null, null, null, 0.8)),
                List.of(),
                "duplicate");

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("more than once"));
    }

    @Test
    void allowsNeitherSourceColumnNorConstantForAGenuinelyUnavailableField() throws Exception {
        // Real observed behavior (see mapping-notes.md): the agent lists a
        // low-confidence, unresolved entry for a field with no data
        // available at all -- e.g. asset_class.FixedIncome.maturity_date
        // when the source has no such column -- rather than omitting it.
        // That's valid, not a structural problem.
        MappingProposal proposal = withOneMapping(
                fm("asset_class.FixedIncome.maturity_date", null, null, null, null, 0.3));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).isEmpty();
    }

    @Test
    void rejectsAVariantValueMapWithNoDiscriminatorSourceColumn() throws Exception {
        MappingProposal proposal = withOneMapping(
                fm("asset_class", null, null, null, Map.of("Equity", "Equity"), 0.9));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("no sourceColumn"));
    }

    @Test
    void rejectsASumTypeFieldWithAMappingEntryButNeitherResolutionMode() throws Exception {
        // Unlike a primitive field, omitting the mapping entirely is how
        // a sum type field says "no data for this" -- an entry that
        // exists but resolves neither way is always malformed, not a
        // legitimate "genuinely unavailable" signal.
        MappingProposal proposal = withOneMapping(
                new MappingProposal.FieldMapping("asset_class", "Class", null, null, null, null, 0.5, "unresolved"));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("neither selectedVariant nor variantValueMap"));
    }

    @Test
    void rejectsAnUnrecognizedTransformationType() throws Exception {
        MappingProposal proposal = withOneMapping(
                new MappingProposal.FieldMapping("quantity", "Quantity", null, null, null,
                        List.of(new MappingProposal.TransformationStep("frobnicate", "1")), 0.9, "test"));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("unrecognized transformation type"));
    }

    @Test
    void rejectsAScaleTransformationOnANonNumberField() throws Exception {
        MappingProposal proposal = withOneMapping(
                new MappingProposal.FieldMapping("account_id", "Account", null, null, null,
                        List.of(new MappingProposal.TransformationStep("scale", "0.01")), 0.9, "test"));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("only NUMBER fields"));
    }

    @Test
    void rejectsAScaleTransformationWithAnUnparseableMultiplier() throws Exception {
        MappingProposal proposal = withOneMapping(
                new MappingProposal.FieldMapping("quantity", "Quantity", null, null, null,
                        List.of(new MappingProposal.TransformationStep("scale", "not-a-number")), 0.9, "test"));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).anyMatch(p -> p.contains("unparseable"));
    }

    @Test
    void allowsAWellFormedScaleTransformation() throws Exception {
        MappingProposal proposal = withOneMapping(
                new MappingProposal.FieldMapping("quantity", "Quantity", null, null, null,
                        List.of(new MappingProposal.TransformationStep("scale", "0.01")), 0.9, "test"));

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).isEmpty();
    }

    // --- Local LLM phase, Step LLM-6's real finding: a model response
    // that decodes to no field mappings at all -- see
    // docs/local-llm-enhancements.md. MappingProposal's own compact
    // constructor makes "null" and "empty" the same reachable state, so
    // one test covers both origins.

    @Test
    void emptyFieldMappingsIsReportedNotSilentlyAccepted() throws Exception {
        MappingProposal proposal = new MappingProposal(List.of(), List.of(), "nothing to map");

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("no field mappings");
    }

    @Test
    void nullFieldMappingsFromTheConstructorIsNormalizedThenReportedTheSameWay() throws Exception {
        // What actually happened against real Qwen 2.5 3B output: the
        // decoded proposal's fieldMappings was null, not an empty list.
        MappingProposal proposal = new MappingProposal(null, null, "malformed model output");

        assertThat(proposal.fieldMappings()).isEmpty();
        assertThat(proposal.unmappedSourceColumns()).isEmpty();

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("no field mappings");
    }

    // External review finding (post Step LLM-6): a null element WITHIN a
    // non-null fieldMappings list, distinct from the null/empty LIST
    // cases already covered above. See docs/local-llm-enhancements.md.

    @Test
    void nullFieldMappingElement_reportedNotCrashed() throws Exception {
        List<MappingProposal.FieldMapping> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(fm("account_id", "Account", null, null, null, 0.9));
        MappingProposal proposal = new MappingProposal(withNull, List.of(), "test");

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("null").contains("malformed");
    }

    @Test
    void nullFieldMappingElement_doesNotPreventValidEntriesFromBeingChecked() throws Exception {
        // A null entry shouldn't short-circuit validation of the OTHER,
        // real entries in the same list -- a genuinely invalid real
        // entry alongside a null one should still be caught.
        List<MappingProposal.FieldMapping> mixed = new java.util.ArrayList<>();
        mixed.add(null);
        mixed.add(fm("nonexistent_field", "Account", null, null, null, 0.9));
        MappingProposal proposal = new MappingProposal(mixed, List.of(), "test");

        List<String> problems = validator.validate(proposal, holdings(), JPMC_COLUMNS);

        assertThat(problems).hasSize(2);
        assertThat(problems).anyMatch(p -> p.contains("null") && p.contains("malformed"));
        assertThat(problems).anyMatch(p -> p.contains("nonexistent_field"));
    }

    private MappingProposal withOneMapping(MappingProposal.FieldMapping fm) {
        return new MappingProposal(List.of(fm), List.of(), "test");
    }

    private MappingProposal.FieldMapping fm(String path, String sourceColumn, String sourceConstant,
            String selectedVariant, Map<String, String> variantValueMap, double confidence) {
        return new MappingProposal.FieldMapping(
                path, sourceColumn, sourceConstant, selectedVariant, variantValueMap, null, confidence, "test note");
    }

    // =====================================================================
    // validateColumnCoverage -- a second, deliberately separate external
    // review finding: the real, severe bug Step LLM-4's field-alias merge
    // introduced. See docs/local-llm-enhancements.md.
    // =====================================================================

    @Test
    void everyObservedColumnMappedOrUnmapped_noCoverageProblems() {
        MappingProposal proposal = new MappingProposal(
                List.of(fm("currency", "Currency", null, "USD", null, 1.0)),
                List.of("Custodian"),
                "test");

        List<String> problems = validator.validateColumnCoverage(proposal, Set.of("Currency", "Custodian"));

        assertThat(problems).isEmpty();
    }

    @Test
    void theExactReviewScenario_deterministicMappingsWithEmptyUnmappedAfterAFailedModelCall() {
        // The real, severe bug: a failed/malformed model call synthesizes
        // an empty MappingProposal (fieldMappings=[], unmappedSourceColumns=[]).
        // Step LLM-4's merge combines that with deterministic mappings for
        // SOME columns, but the merged proposal's unmappedSourceColumns
        // still reflects the empty fallback, not reality -- so a column
        // the model was supposed to handle (here, "Valuation Px") vanishes
        // from BOTH lists with no signal anything was wrong. This is
        // exactly that merged proposal, constructed directly.
        MappingProposal mergedAfterFailedModelCall = new MappingProposal(
                List.of(
                        fm("currency", "Currency", null, "USD", null, 1.0),
                        fm("custodian", "Custodian", null, null, null, 1.0)),
                List.of(), // <-- the bug: empty, inherited from the failed call's synthesized empty proposal
                "test");

        List<String> problems = validator.validateColumnCoverage(
                mergedAfterFailedModelCall, Set.of("Currency", "Custodian", "Valuation Px"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Valuation Px").contains("silently unaccounted for");
    }

    @Test
    void unmappedSourceColumnsListingSomethingNeverObserved_isReported() {
        MappingProposal proposal = new MappingProposal(
                List.of(), List.of("Not A Real Column"), "test");

        List<String> problems = validator.validateColumnCoverage(proposal, Set.of("Currency"));

        // Two problems: "Not A Real Column" was never observed, AND
        // "Currency" (the one real observed column) is still
        // unaccounted for -- both are independently true and both
        // should be reported, not just whichever is checked first.
        assertThat(problems).hasSize(2);
        assertThat(problems).anyMatch(p -> p.contains("Not A Real Column") && p.contains("never actually observed"));
        assertThat(problems).anyMatch(p -> p.contains("Currency") && p.contains("silently unaccounted for"));
    }

    @Test
    void columnBothMappedAndListedAsUnmapped_isContradictory() {
        MappingProposal proposal = new MappingProposal(
                List.of(fm("currency", "Currency", null, "USD", null, 1.0)),
                List.of("Currency"),
                "test");

        List<String> problems = validator.validateColumnCoverage(proposal, Set.of("Currency"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Currency").contains("contradictory");
    }

    @Test
    void sourceConstantBasedMappingDoesNotCountAsCoveringAColumn() {
        // A field mapped via sourceConstant doesn't correspond to any
        // observed column -- it must not accidentally satisfy coverage
        // for some unrelated column.
        MappingProposal proposal = new MappingProposal(
                List.of(fm("as_of_date", null, "2026-01-15", null, null, 1.0)),
                List.of(),
                "test");

        List<String> problems = validator.validateColumnCoverage(proposal, Set.of("Currency"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Currency").contains("silently unaccounted for");
    }

    @Test
    void emptyObservedColumns_noCoverageProblemsRegardlessOfProposalContent() {
        MappingProposal proposal = new MappingProposal(List.of(), List.of(), "test");

        List<String> problems = validator.validateColumnCoverage(proposal, Set.of());

        assertThat(problems).isEmpty();
    }
}
