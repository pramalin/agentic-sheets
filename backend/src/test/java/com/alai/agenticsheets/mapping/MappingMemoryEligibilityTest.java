package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingMemoryEligibilityTest {

    private MappingProposal.FieldMapping sourceColumnMapping(String path, String column) {
        return new MappingProposal.FieldMapping(path, column, null, null, null, List.of(), 0.9, null);
    }

    private MappingProposal.FieldMapping sourceConstantMapping(String path, String constant) {
        return new MappingProposal.FieldMapping(path, null, constant, null, null, List.of(), 0.7, null);
    }

    private MappingProposal.FieldMapping selectedVariantMapping(String path, String variant) {
        return new MappingProposal.FieldMapping(path, null, null, variant, null, List.of(), 0.85, null);
    }

    private MappingProposal.FieldMapping variantValueMapMapping(String path, String column) {
        return new MappingProposal.FieldMapping(
                path, column, null, null, Map.of("Equity", "Equity", "Fixed Income", "FixedIncome"),
                List.of(), 0.9, null);
    }

    @Test
    void eligibleWhenEveryFieldGeneralizes() {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        sourceColumnMapping("account_id", "Account"),
                        variantValueMapMapping("asset_class", "Asset Class")),
                List.of(),
                "summary");

        MappingMemoryEligibility.Result result = MappingMemoryEligibility.check(proposal);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void ineligibleWhenAnyFieldUsesSourceConstant() {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        sourceColumnMapping("account_id", "Account"),
                        sourceConstantMapping("as_of_date", "2026-02-01")),
                List.of(),
                "summary");

        MappingMemoryEligibility.Result result = MappingMemoryEligibility.check(proposal);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().get(0)).contains("as_of_date").contains("sourceConstant");
    }

    @Test
    void ineligibleWhenAnyFieldUsesSelectedVariant() {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        sourceColumnMapping("account_id", "Account"),
                        selectedVariantMapping("currency", "USD")),
                List.of(),
                "summary");

        MappingMemoryEligibility.Result result = MappingMemoryEligibility.check(proposal);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().get(0)).contains("currency").contains("selectedVariant");
    }

    @Test
    void ineligibleWhenBothProblemsPresentAndReportsBoth() {
        MappingProposal proposal = new MappingProposal(
                List.of(
                        sourceConstantMapping("as_of_date", "2026-02-01"),
                        selectedVariantMapping("currency", "USD")),
                List.of(),
                "summary");

        MappingMemoryEligibility.Result result = MappingMemoryEligibility.check(proposal);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).hasSize(2);
    }

    @Test
    void aBlankSourceConstantOrSelectedVariantDoesNotDisqualify() {
        // isSet() treats blank/empty the same as unset -- a defensive
        // case, not something the agent should ever actually produce,
        // but worth confirming the check doesn't misfire on it.
        MappingProposal.FieldMapping blankConstant =
                new MappingProposal.FieldMapping("account_id", "Account", "", null, null, List.of(), 0.9, null);
        MappingProposal proposal = new MappingProposal(List.of(blankConstant), List.of(), "summary");

        assertThat(MappingMemoryEligibility.check(proposal).eligible()).isTrue();
    }
}
