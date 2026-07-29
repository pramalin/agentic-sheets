package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalModelPromptRendererTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();
    private final CanonicalModelPromptRenderer renderer = new CanonicalModelPromptRenderer();

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }

    @Test
    void rendersHoldingsIncludingTheSumTypeAndSynonyms() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model);

        assertThat(rendered).contains("Canonical model: Holdings (version 1)");

        // Plain product field, required
        assertThat(rendered).contains("- as_of_date: Date (required) [format: yyyy-MM-dd]");

        // Optional field
        assertThat(rendered).contains("- custodian: String (optional)");

        // Sum type: variant names listed, FixedIncome's own fields shown
        // under a variant-qualified path
        assertThat(rendered).contains("- asset_class: exactly one of [Equity, FixedIncome, Cash, Alternative]");
        assertThat(rendered).contains("- asset_class.FixedIncome.maturity_date: Date (optional)");
        assertThat(rendered).contains("- variant Equity: no extra fields");

        // Synonyms attached to the field they describe
        assertThat(rendered).contains("synonyms: market value, mkt val, mv, current value, value");
    }

    @Test
    void marketRateBookValueHasNoSumTypeAtTheTopLevelSinceItsAProductType() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/market_rate_book_value.yaml"));

        String rendered = renderer.render(model);

        assertThat(rendered).contains("- market_rate: Number (required)");
        assertThat(rendered).contains("- book_value: Number (required)");
        // Both present directly at the top level, not nested behind a sum
        // type variant choice -- this is the resolved sum-vs-product
        // design decision made real. (currency is legitimately still a
        // sum type here -- an enum-like one -- so this only checks that
        // market_rate/book_value specifically aren't behind one.)
        assertThat(rendered).doesNotContain("market_rate.");
        assertThat(rendered).doesNotContain("book_value.");
    }
}
