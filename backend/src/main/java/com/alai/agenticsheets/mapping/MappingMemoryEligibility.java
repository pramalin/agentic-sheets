package com.alai.agenticsheets.mapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 10A's conservative eligibility rule: a proposal is safe to
 * remember and reapply against a *different* file only if every field
 * mapping in it generalizes across files with the same column
 * structure. {@code sourceColumn}, {@code transformations}, and
 * {@code variantValueMap} all do -- they're rules applied to whatever
 * the new file's actual data turns out to be. {@code sourceConstant}
 * and {@code selectedVariant} don't: both are literal facts about the
 * *specific file* the agent saw, not general rules.
 *
 * {@code sourceConstant} is the clearer case -- a banner-derived date
 * like {@code "2026-02-01"} has nothing to do with column structure at
 * all; reusing it against a different file's data would silently claim
 * the previous file's date as the new file's fact.
 *
 * {@code selectedVariant} is subtler but equally real: confirmed by
 * reading {@link CanonicalRowBuilder} directly, it's trusted
 * immediately with zero row-level verification, unlike
 * {@code variantValueMap}, which looks up each row's own discriminator
 * value and errors on anything unmapped. A file that happened to be
 * all-USD doesn't mean the next file with the same columns will be.
 *
 * Deliberately all-or-nothing at the proposal level, not per-field --
 * a proposal with even one disqualifying field mapping is not
 * remembered at all for Step 10A. A future Step 10B (see
 * {@code inbox-scanner-notes.md}'s sibling doc for Step 9's own
 * "named, not silently dropped" convention) could remember the safe
 * fields individually and only fall back to the agent for the
 * disqualifying ones -- deliberately not built now.
 */
public final class MappingMemoryEligibility {

    private MappingMemoryEligibility() {
    }

    public record Result(boolean eligible, List<String> reasons) {
    }

    public static Result check(MappingProposal proposal) {
        List<String> reasons = new ArrayList<>();
        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
            if (isSet(fm.sourceConstant())) {
                reasons.add("'" + fm.canonicalFieldPath() + "' uses sourceConstant ('" + fm.sourceConstant()
                        + "') -- a literal value specific to this file, not safe to reapply to a different one");
            }
            if (isSet(fm.selectedVariant())) {
                reasons.add("'" + fm.canonicalFieldPath() + "' uses selectedVariant ('" + fm.selectedVariant()
                        + "') -- CanonicalRowBuilder trusts this with no row-level verification, so a "
                        + "different file's actual data could silently be wrong under a reused selection");
            }
        }
        return new Result(reasons.isEmpty(), reasons);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
