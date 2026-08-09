package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

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

    // A second real finding, this time the opposite direction --
    // reproduced against real Qwen 2.5 3B output: security_description
    // rendered back as description, and
    // asset_class.FixedIncome.maturity_date rendered back as a bare
    // maturity_date. See docs/local-llm-enhancements.md's "Seventh real
    // run" section for the full account.

    @Test
    void explicitlyInstructsAgainstShorteningAPath() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model);

        assertThat(rendered).contains("do NOT shorten or abbreviate a path");
        // The two real, named examples this instruction was built from,
        // not a generic warning -- concrete enough for the model to
        // pattern-match against.
        assertThat(rendered).contains("\"security_description\" must never become \"description\"");
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

    // notProvidedFields (post-benchmark hardening -- see
    // docs/local-llm-enhancements.md's "twelfth real run" section): a
    // structural fix for the FixedIncome sub-field hallucination five
    // separate prompt-wording attempts across five real benchmark
    // rounds all failed to reliably prevent -- omit the field from the
    // listing entirely rather than instruct the model not to invent it.

    @Test
    void excludingOneSubFieldOmitsOnlyThatPathAndLeavesItsSiblingsVisible() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model, Set.of("asset_class.FixedIncome.maturity_date"));

        // "- path:" specifically, not a bare substring search -- the
        // instructional preamble above the field listing already uses
        // this exact path as a worked example ("do NOT shorten or
        // abbreviate a path", added in an earlier round), so a plain
        // doesNotContain(bare path) would fail even when exclusion is
        // working correctly, since that unrelated prose text isn't
        // filtered by excludedFieldPaths at all -- confirmed by a real
        // run: the field listing itself was already correct, only this
        // assertion was checking the wrong thing.
        assertThat(rendered).doesNotContain("- asset_class.FixedIncome.maturity_date:");
        // Siblings under the same variant, and the sum type field
        // itself, still render normally.
        assertThat(rendered).contains("- asset_class.FixedIncome.coupon_rate: Number (optional)");
        assertThat(rendered).contains("- asset_class.FixedIncome.credit_rating: String (optional)");
        assertThat(rendered).contains("- asset_class: exactly one of [Equity, FixedIncome, Cash, Alternative]");
    }

    @Test
    void excludingATopLevelFieldOmitsItEntirely() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model, Set.of("custodian"));

        assertThat(rendered).doesNotContain("- custodian:");
        // A field with a similar-looking name isn't accidentally caught
        // by a naive substring exclusion -- the check is exact-path,
        // not prefix or substring.
        assertThat(rendered).contains("- currency:");
    }

    @Test
    void excludingEveryChildOfAVariantShowsAnHonestMessageNotADanglingHeader() throws Exception {
        // The real bug an external review caught in an earlier draft:
        // checking the schema's own field count (variant.fields().isEmpty())
        // doesn't account for exclusions. If a variant has fields but
        // every one of them is excluded, the old logic still took the
        // "has fields" branch, printed "variant FixedIncome:", then
        // rendered nothing beneath it -- a dangling, confusing header.
        // This is FixedIncome's exact real shape: three optional fields,
        // all three excludable via configuration.
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model, Set.of(
                "asset_class.FixedIncome.maturity_date",
                "asset_class.FixedIncome.coupon_rate",
                "asset_class.FixedIncome.credit_rating"));

        // Same "- path:" precision as the previous test -- maturity_date
        // specifically also appears in the instructional preamble's own
        // worked example, unrelated to exclusion.
        assertThat(rendered).doesNotContain("- asset_class.FixedIncome.maturity_date:");
        assertThat(rendered).doesNotContain("- asset_class.FixedIncome.coupon_rate:");
        assertThat(rendered).doesNotContain("- asset_class.FixedIncome.credit_rating:");
        assertThat(rendered).contains("- variant FixedIncome: no source-provided extra fields for this client");
        // Distinct from a variant that genuinely has no extra fields in
        // the schema at all (Equity, per the first test in this file) --
        // the two messages must not collide, since they mean different
        // things to a human debugging a rendered prompt.
        assertThat(rendered).contains("- variant Equity: no extra fields");
        assertThat(rendered).doesNotContain("variant FixedIncome: no extra fields");
    }
}
