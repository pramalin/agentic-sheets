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

    private MappingProposal withOneMapping(MappingProposal.FieldMapping fm) {
        return new MappingProposal(List.of(fm), List.of(), "test");
    }

    private MappingProposal.FieldMapping fm(String path, String sourceColumn, String sourceConstant,
            String selectedVariant, Map<String, String> variantValueMap, double confidence) {
        return new MappingProposal.FieldMapping(
                path, sourceColumn, sourceConstant, selectedVariant, variantValueMap, null, confidence, "test note");
    }
}
