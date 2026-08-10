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
    // run" section for the full account. Post-review hardening (see
    // that same doc's "review, client-config governance" section): the
    // worked examples were later made generic rather than using the
    // real field names the model had shown a specific fixation on --
    // this test was updated to match, checking for the instruction's
    // presence and the (now-generic) example pair actually used, not
    // the original real-field-name pair.

    @Test
    void explicitlyInstructsAgainstShorteningAPath() throws Exception {
        CanonicalModel model = parser.parse(resource("canonical-models/holdings.yaml"));

        String rendered = renderer.render(model);

        assertThat(rendered).contains("do NOT shorten or abbreviate a path");
        // A generic, made-up example pair, not tied to any real
        // Holdings field -- deliberate, so the prompt doesn't keep
        // repeating the exact field names the model has shown a
        // specific fixation on, even in a "don't do this" framing.
        assertThat(rendered).contains("\"account_holder_name\" must never become \"holder_name\"");
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

        // "- path:" specifically, not a bare substring search -- a
        // defensive habit from an earlier round when the instructional
        // preamble above the field listing still used this exact path
        // as a worked example, which made a bare substring search
        // fail even when exclusion was working correctly (see this
        // file's own git history, or docs/local-llm-enhancements.md's
        // "review, client-config governance" section, for why those
        // specific field-name examples were later made generic). Kept
        // precise even now that the preamble no longer mentions this
        // path at all -- checking the field-listing-specific format is
        // simply the more correct thing to assert regardless.
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

        // Same "- path:" precision as the previous test, now kept for
        // defensive consistency rather than to dodge a live preamble
        // collision -- see that test's own comment for the full
        // history of why this pattern was originally needed.
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
