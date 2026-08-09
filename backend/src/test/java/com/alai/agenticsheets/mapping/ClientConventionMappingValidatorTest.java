package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Post-benchmark hardening (see {@code docs/local-llm-enhancements.md}'s
 * "twelfth real run" section). {@link ClientConventionMappingValidator}
 * is the authoritative half of {@code notProvidedFields} enforcement --
 * {@link CanonicalModelPromptRenderer} omitting a field from the prompt
 * is a hint, not a guarantee; this is what makes the convention actually
 * hold regardless of how a mapping was produced.
 */
class ClientConventionMappingValidatorTest {

    private final ClientConventionMappingValidator validator = new ClientConventionMappingValidator();

    private ClientConfig withNotProvided(List<String> notProvidedFields) {
        return new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(),
                Map.of("Holdings", new ClientModelConventions(Map.of(), Map.of(), notProvidedFields)));
    }

    private MappingProposal.FieldMapping mapping(String canonicalFieldPath, String sourceColumn) {
        return new MappingProposal.FieldMapping(
                canonicalFieldPath, sourceColumn, null, null, null, List.of(), 1.0, null);
    }

    @Test
    void aCleanProposalWithNoConfiguredNotProvidedFieldsPassesWithoutInspectingAnything() {
        ClientConfig client = withNotProvided(List.of());
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency")), List.of(), null);

        assertThat(validator.validate(proposal, client, "Holdings")).isEmpty();
    }

    @Test
    void rejectsAProposalMappingADeclaredNotProvidedField() {
        // The exact scenario this class exists for: a model (or a
        // human amending by hand) maps a field the client's config
        // declares this feed never provides, regardless of how the
        // path got into the proposal.
        ClientConfig client = withNotProvided(List.of("asset_class.FixedIncome.maturity_date"));
        MappingProposal proposal = new MappingProposal(
                List.of(
                        mapping("currency", "Currency"),
                        mapping("asset_class.FixedIncome.maturity_date", "Maturity Date")),
                List.of(), null);

        List<String> problems = validator.validate(proposal, client, "Holdings");
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("asset_class.FixedIncome.maturity_date").contains("jpmc");
    }

    @Test
    void aProposalThatDoesNotTouchAnyNotProvidedFieldPasses() {
        ClientConfig client = withNotProvided(List.of("asset_class.FixedIncome.maturity_date"));
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency"), mapping("market_price", "Price")),
                List.of(), null);

        assertThat(validator.validate(proposal, client, "Holdings")).isEmpty();
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
        ClientConfig client = withNotProvided(List.of(
                "asset_class.FixedIncome.maturity_date",
                "asset_class.FixedIncome.coupon_rate"));
        MappingProposal proposal = new MappingProposal(
                List.of(
                        mapping("asset_class.FixedIncome.maturity_date", "Maturity Date"),
                        mapping("asset_class.FixedIncome.coupon_rate", "Coupon Rate")),
                List.of(), null);

        assertThat(validator.validate(proposal, client, "Holdings")).hasSize(2);
    }

    @Test
    void aClientWithNoConventionsConfiguredForThisModelAtAllPasses() {
        // conventions().get(modelId) returning null (no conventions
        // block for this model at all) must short-circuit cleanly, not
        // throw -- the common case for most models a client feeds.
        ClientConfig client = new ClientConfig("jpmc", "yyyy-MM-dd", Map.of(), Map.of());
        MappingProposal proposal = new MappingProposal(
                List.of(mapping("currency", "Currency")), List.of(), null);

        assertThat(validator.validate(proposal, client, "Holdings")).isEmpty();
    }
}
