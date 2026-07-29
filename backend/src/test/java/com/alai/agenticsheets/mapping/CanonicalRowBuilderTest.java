package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.DateValue;
import com.alai.agenticsheets.canonical.NumberValue;
import com.alai.agenticsheets.canonical.RecordValue;
import com.alai.agenticsheets.canonical.StringValue;
import com.alai.agenticsheets.canonical.VariantValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRowBuilderTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();
    private final CanonicalRowBuilder builder = new CanonicalRowBuilder();

    private static final ClientConfig JPMC = new ClientConfig("jpmc", "yyyy-MM-dd");
    private static final ClientConfig PIMCO = new ClientConfig("pimco", "MM/dd/yyyy");

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private Map<String, MappingProposal.FieldMapping> byPath(MappingProposal.FieldMapping... mappings) {
        return List.of(mappings).stream()
                .collect(Collectors.toMap(MappingProposal.FieldMapping::canonicalFieldPath, m -> m));
    }

    private MappingProposal.FieldMapping column(String path, String sourceColumn) {
        return new MappingProposal.FieldMapping(path, sourceColumn, null, null, null, 0.9, "test");
    }

    private MappingProposal.FieldMapping constant(String path, String value) {
        return new MappingProposal.FieldMapping(path, null, value, null, null, 0.9, "test");
    }

    private MappingProposal.FieldMapping selectedVariant(String path, String variant) {
        return new MappingProposal.FieldMapping(path, null, null, variant, null, 0.9, "test");
    }

    private MappingProposal.FieldMapping variantMap(String path, String sourceColumn, Map<String, String> map) {
        return new MappingProposal.FieldMapping(path, sourceColumn, null, null, map, 0.9, "test");
    }

    @Test
    void buildsAFullyValidRowWithNoErrors() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));

        Map<String, String> row = Map.of(
                "As Of Date", "2026-01-15",
                "Account", "ACC-1001",
                "CUSIP", "037833100",
                "Quantity", "5000",
                "Market Value", "926500.00");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
        RecordValue record = (RecordValue) result.value();
        assertThat(((DateValue) record.fields().get("as_of_date")).value().toString()).isEqualTo("2026-01-15");
        assertThat(((StringValue) record.fields().get("account_id")).value()).isEqualTo("ACC-1001");
        assertThat(((NumberValue) record.fields().get("quantity")).value()).isEqualByComparingTo(new BigDecimal("5000"));
        VariantValue assetClass = (VariantValue) record.fields().get("asset_class");
        assertThat(assetClass.caseName()).isEqualTo("Equity");
        assertThat(assetClass.payload().fields()).isEmpty();
    }

    @Test
    void reportsAnErrorForAMissingRequiredField() throws Exception {
        // account_id has no mapping at all
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "CUSIP", "037833100",
                "Quantity", "5000", "Market Value", "926500.00");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("account_id"));
    }

    @Test
    void allowsAMissingOptionalFieldWithNoError() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        // custodian is optional and has no mapping at all here
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "ACC-1001",
                "CUSIP", "037833100", "Quantity", "5000", "Market Value", "926500.00");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
        RecordValue record = (RecordValue) result.value();
        assertThat(record.fields().get("custodian")).isInstanceOf(com.alai.agenticsheets.canonical.AbsentValue.class);
    }

    @Test
    void parsesADateInTheClientsConfiguredNonIsoFormat() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "Report Date"),
                constant("client_id", "pimco"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("Report Date", "05/01/2025", "Account", "A", "CUSIP", "B",
                "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, PIMCO, row);

        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
        RecordValue record = (RecordValue) result.value();
        assertThat(((DateValue) record.fields().get("as_of_date")).value().toString()).isEqualTo("2025-05-01");
    }

    @Test
    void reportsAnErrorForAnUnparseableDate() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                // The exact real-world failure observed live with MetLife:
                // the agent stuffed an explanation into sourceConstant
                // instead of just the value.
                constant("as_of_date", "filename: holdings_metlife_20260201.xlsx -> 2026-02-01"),
                constant("client_id", "metlife"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("Account", "A", "CUSIP", "B", "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("as_of_date") && e.contains("could not be parsed"));
    }

    @Test
    void reportsAnErrorForANonNumericValue() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "Equity"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "A", "CUSIP", "B",
                "Quantity", "not-a-number", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("quantity") && e.contains("not a valid number"));
    }

    @Test
    void resolvesASumTypeVariantViaVariantValueMap() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                variantMap("asset_class", "Class", Map.of("Fixed Income", "FixedIncome")),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "A", "CUSIP", "B",
                "Class", "Fixed Income", "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
        RecordValue record = (RecordValue) result.value();
        VariantValue assetClass = (VariantValue) record.fields().get("asset_class");
        assertThat(assetClass.caseName()).isEqualTo("FixedIncome");
    }

    @Test
    void reportsAnErrorWhenTheRowsValueIsntInTheVariantValueMap() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                variantMap("asset_class", "Class", Map.of("Equity", "Equity")), // "Bond" not in the map
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "A", "CUSIP", "B",
                "Class", "Bond", "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("asset_class") && e.contains("Bond"));
    }

    @Test
    void constructsFixedIncomeVariantFieldsFromVariantQualifiedPaths() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                selectedVariant("asset_class", "FixedIncome"),
                column("asset_class.FixedIncome.coupon_rate", "Coupon"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "A", "CUSIP", "B",
                "Coupon", "4.25", "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
        RecordValue record = (RecordValue) result.value();
        VariantValue assetClass = (VariantValue) record.fields().get("asset_class");
        assertThat(assetClass.caseName()).isEqualTo("FixedIncome");
        NumberValue coupon = (NumberValue) assetClass.payload().fields().get("coupon_rate");
        assertThat(coupon.value()).isEqualByComparingTo(new BigDecimal("4.25"));
        // maturity_date and credit_rating are optional and unmapped here -- absent, not an error
        assertThat(assetClass.payload().fields().get("maturity_date"))
                .isInstanceOf(com.alai.agenticsheets.canonical.AbsentValue.class);
    }

    @Test
    void reportsAnErrorWhenASumTypeFieldHasNeitherResolutionMode() throws Exception {
        Map<String, MappingProposal.FieldMapping> mappings = byPath(
                column("as_of_date", "As Of Date"),
                constant("client_id", "jpmc"),
                column("account_id", "Account"),
                column("security_id", "CUSIP"),
                // asset_class mapping present but neither selectedVariant nor variantValueMap set
                new MappingProposal.FieldMapping("asset_class", "Class", null, null, null, 0.5, "unresolved"),
                column("quantity", "Quantity"),
                column("market_value", "Market Value"),
                selectedVariant("currency", "USD"));
        Map<String, String> row = Map.of("As Of Date", "2026-01-15", "Account", "A", "CUSIP", "B",
                "Class", "Equity", "Quantity", "1", "Market Value", "1");

        CanonicalRowBuilder.Result result = builder.build(holdings().root(), mappings, JPMC, row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("neither selectedVariant nor variantValueMap"));
    }
}
