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
 */
public record ClientModelConventions(
        Map<String, List<String>> fieldAliases,
        Map<String, Map<String, String>> variantValues) {
}
