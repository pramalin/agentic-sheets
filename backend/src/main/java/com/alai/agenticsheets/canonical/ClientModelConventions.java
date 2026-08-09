package com.alai.agenticsheets.canonical;

import java.util.List;
import java.util.Map;

/**
 * One canonical model's worth of a client's durable source-side
 * vocabulary -- Local LLM phase, Step LLM-3 (see
 * {@code docs/local-llm-enhancements.md}). Field name aliases and
 * canonical-variant value mappings that are true of this client's data
 * in general, not specific to one file.
 *
 * <p>Deliberately distinct from Step 10's mapping memory:
 * {@code MappingMemory} remembers a complete approved result for one
 * particular source column structure; this is smaller, more durable
 * knowledge -- "JPMC calls the currency column 'Ccy'," "JPMC's 'Fixed
 * Income' value means canonical variant {@code FixedIncome}" -- true
 * regardless of which file it shows up in. Consumed by the deterministic
 * resolver ahead of canonical-name matching once Step LLM-4 wires the
 * two together; this record and its validation (Step LLM-3) exist first,
 * on their own, so they can be exercised and tested independently of
 * that integration.
 *
 * <p>Scoped per canonical model (keyed by {@code modelId} on
 * {@link ClientConfig#conventions()}), not flat across a client, because
 * one client can feed more than one canonical model and a field path
 * like {@code currency} only means one specific thing within one
 * specific model's ADT.
 *
 * @param fieldAliases canonical field path -> known alternate source
 * column header names for that field (e.g.
 * {@code currency -> [Ccy, Curr]}). Never a substitute for a human
 * reviewing a proposal -- an alias match still produces a proposal a
 * person approves, same as any other source of column-mapping evidence.
 * @param variantValues canonical sum-type field path -> {observed source
 * value -> canonical variant name} (e.g.
 * {@code asset_class -> {"Fixed Income" -> "FixedIncome"}}).
 * @param notProvidedFields canonical field paths this client's data for
 * this model is durably known to never carry a value for -- Local LLM
 * phase, post-benchmark hardening (see
 * {@code docs/local-llm-enhancements.md}'s "twelfth real run" section).
 * Named deliberately narrower than an earlier draft's
 * {@code notApplicableFields}: the claim isn't that the canonical
 * concept doesn't apply to this client's domain, only that this
 * client's specific source *feed* never provides it -- a claim about
 * data format, not domain semantics. Every entry must reference a real,
 * genuinely OPTIONAL field ({@link ClientConventionsValidator} rejects
 * both a nonexistent path and a required one -- excluding a required
 * field would make this client's Holdings feed permanently
 * unsatisfiable). Only meant for a durable, asserted fact about a
 * client's real feed, not "absent from one sample file" -- most useful
 * for a sum type's own variant-specific sub-fields (e.g.
 * {@code asset_class.FixedIncome.maturity_date}) when a client's feed
 * simply never carries that level of detail. Consumed two ways:
 * {@link CanonicalModelPromptRenderer} omits these paths from what the
 * model is ever shown (a structural fix, not a prompt instruction --
 * five distinct prompt-wording attempts across five real benchmark
 * rounds all failed to reliably stop a 3B model from inventing or
 * repurposing a column for exactly these sub-fields, while every
 * deterministic mechanism in this whole phase held up perfectly every
 * time); and a proposal -- model-produced or human-amended -- that maps
 * one of these paths anyway is rejected outright, an authoritative
 * constraint, not merely a rendering hint that a model could still
 * defeat by producing the path from its own general knowledge despite
 * never seeing it in this specific prompt.
 */
public record ClientModelConventions(
        Map<String, List<String>> fieldAliases,
        Map<String, Map<String, String>> variantValues,
        List<String> notProvidedFields) {
}
