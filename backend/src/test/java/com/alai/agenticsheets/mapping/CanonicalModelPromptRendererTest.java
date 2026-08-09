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

    // External review finding (post Step LLM-6): reproduced twice,
    // identically, against real Qwen 2.5 3B output -- the model prefixed
    // every canonicalFieldPath with the model's own name ("Holdings.currency"
    // instead of "currency"). A plausible, not confirmed, cause: this
    // header text puts "Holdings" immediately above a field listing,
    // which could read as an implicit namespace. See
    // docs/local-llm-enhancements.md for the full account, including
    // that this remains unconfirmed pending a raw-logged re-run.

    @Test
    void explicitlyInstructsAgainstPrefixingAPathWithTheModelName() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model);

        assertThat(rendered).contains("do NOT prefix a path with the canonical model's own name");
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
