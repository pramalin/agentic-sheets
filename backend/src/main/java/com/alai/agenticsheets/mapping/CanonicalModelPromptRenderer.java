package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalType;
import com.alai.agenticsheets.canonical.OptionType;
import com.alai.agenticsheets.canonical.PrimitiveType;
import com.alai.agenticsheets.canonical.RecordType;
import com.alai.agenticsheets.canonical.SumType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns any team's parsed {@link CanonicalModel} into a flattened,
 * plain-text field listing the mapping agent can reason about --
 * {@code agentic-sheets} has no compile-time Java type for "a Holdings
 * row" or "a MarketRateBookValue row" (they're loaded from YAML at
 * runtime), so this walks the generic {@link CanonicalType} tree
 * instead of binding to a fixed shape.
 *
 * Field paths use dots to reach into a sum type's variant fields (e.g.
 * {@code asset_class.FixedIncome.maturity_date}), matching exactly what
 * {@link MappingProposal.FieldMapping#canonicalFieldPath()} is expected
 * to reference back.
 *
 * <p>As of the Local LLM phase's Step LLM-4 aftermath (see
 * {@code docs/local-llm-enhancements.md}): {@link #render} explicitly
 * instructs against prefixing a path with the model's own name, added
 * after real Qwen 2.5 3B output reproduced that exact confusion
 * identically across two separate runs ({@code Holdings.currency}
 * instead of {@code currency}, for every field, both times). A
 * plausible cause -- the "Canonical model: X" header sitting immediately
 * above the field listing reading as an implicit namespace -- not a
 * confirmed one; this instruction is a low-risk, easily-reverted
 * mitigation attempt, not a diagnosis. Whether it actually helps needs
 * a real re-run to know, ideally with raw response logging enabled to
 * see the model's output directly rather than only its downstream
 * validation failures.
 *
 * <p>A later real run (see the same doc's "Seventh real run" section)
 * found the opposite failure mode: the model shortening a path rather
 * than adding to it -- {@code security_description} rendered back as
 * {@code description}, and {@code asset_class.FixedIncome.maturity_date}
 * rendered back as a bare {@code maturity_date}. The original "use the
 * path EXACTLY as shown" instruction was worded entirely around not
 * adding an incorrect prefix; {@link #render} now explicitly calls out
 * shortening as an equally wrong failure mode, with the same two
 * real examples named directly. Same caveat as above: an attempt, not
 * a confirmed fix, until a real run says otherwise.
 *
 * <p>Five distinct prompt-wording attempts across five real benchmark
 * rounds all failed to reliably stop the model from inventing or
 * repurposing a source column for one of a sum type's own
 * variant-specific sub-fields (see {@code docs/local-llm-enhancements.md}'s
 * "eleventh real run" section for the last of them) -- a structural fix
 * followed instead, on an external review's own suggestion: a client
 * can now declare, via
 * {@link com.alai.agenticsheets.canonical.ClientModelConventions#notProvidedFields()},
 * that specific optional fields are durably known to never appear in
 * their data. {@link #render(CanonicalModel, Set)} omits those paths
 * from the listing entirely -- if the model never sees a field as an
 * option, it cannot hallucinate a source for it, a different kind of
 * fix from every instruction attempted before it, none of which changed
 * what the model was shown, only what it was told to do with what it
 * saw. Enforced twice, not once: this omission is a hint an unrelated
 * confusion could still defeat (a model can produce a path it never saw
 * in this specific prompt from its own general training); the
 * authoritative backstop is {@link ClientConventionMappingValidator},
 * which rejects a proposal -- model-produced or human-amended -- that
 * maps one of these paths regardless of whether the renderer omitted it.
 */
@Component
public class CanonicalModelPromptRenderer {

    public String render(CanonicalModel model) {
        return render(model, Set.of());
    }

    /** @param excludedFieldPaths canonical field paths to omit from the
      * rendered listing entirely -- see this class's own javadoc for the
      * full reasoning. Sourced from
      * {@link com.alai.agenticsheets.canonical.ClientModelConventions#notProvidedFields()}
      * by {@link AgentMappingProposalService}. */
    public String render(CanonicalModel model, Set<String> excludedFieldPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("Canonical model: ").append(model.modelId())
                .append(" (version ").append(model.version()).append(")\n");
        sb.append("Fields below are identified by path (dot-separated for a sum ")
                .append("type's variant fields). Use the path EXACTLY as shown, e.g. \"")
                .append("currency\" or \"asset_class.FixedIncome.maturity_date\" -- do NOT prefix a ")
                .append("path with the canonical model's own name (\"").append(model.modelId())
                .append("\" above is the name of this schema, not part of any field's path). ")
                .append("Equally, do NOT shorten or abbreviate a path -- \"security_description\" ")
                .append("must never become \"description\", and \"asset_class.FixedIncome.maturity_date\" ")
                .append("must never become just \"maturity_date\". A field's whole path, including any ")
                .append("prefix or dotted variant-qualifier, is one indivisible identifier -- copy it ")
                .append("character for character from the listing below, don't reconstruct or ")
                .append("paraphrase it from memory.\n\n");
        renderField(sb, "", model.root(), true, "", model.synonyms(), excludedFieldPaths);
        return sb.toString();
    }

    private void renderField(StringBuilder sb, String path, CanonicalType type, boolean required,
            String indent, Map<String, List<String>> synonyms, Set<String> excludedFieldPaths) {
        if (excludedFieldPaths.contains(path)) {
            return;
        }
        switch (type) {
            case OptionType o -> renderField(sb, path, o.inner(), false, indent, synonyms, excludedFieldPaths);

            case PrimitiveType p -> {
                sb.append(indent).append("- ").append(path).append(": ").append(primitiveName(p))
                        .append(required ? " (required)" : " (optional)");
                if (p.format() != null) {
                    sb.append(" [format: ").append(p.format()).append("]");
                }
                sb.append("\n");
                appendSynonyms(sb, indent, path, synonyms);
            }

            case SumType s -> {
                sb.append(indent).append("- ").append(path).append(": exactly one of [")
                        .append(String.join(", ", s.variants().keySet())).append("]")
                        .append(required ? " (required)" : " (optional)").append("\n");
                appendSynonyms(sb, indent, path, synonyms);
                for (Map.Entry<String, RecordType> entry : s.variants().entrySet()) {
                    String variantPath = path + "." + entry.getKey();
                    RecordType variant = entry.getValue();
                    // An external review caught a real bug in an earlier
                    // draft here: checking variant.fields().isEmpty()
                    // (the SCHEMA's own field count) doesn't account for
                    // exclusions -- if a variant has fields but every one
                    // of them is excluded, that earlier version still
                    // took the "has fields" branch, printed the
                    // "variant X:" header, then rendered nothing beneath
                    // it (each child's own renderField call returning
                    // immediately via the exclusion check above), leaving
                    // a dangling, confusing header with no content.
                    // Compute the actually-visible children first instead,
                    // and render an explicit message distinguishing "this
                    // variant genuinely has no extra fields in the
                    // schema" from "it has fields, but none this client's
                    // data provides."
                    List<Map.Entry<String, CanonicalType>> visibleFields = variant.fields().entrySet().stream()
                            .filter(fieldEntry -> !excludedFieldPaths.contains(
                                    variantPath + "." + fieldEntry.getKey()))
                            .toList();
                    if (visibleFields.isEmpty()) {
                        sb.append(indent).append("  - variant ").append(entry.getKey())
                                .append(variant.fields().isEmpty()
                                        ? ": no extra fields\n"
                                        : ": no source-provided extra fields for this client\n");
                    } else {
                        sb.append(indent).append("  - variant ").append(entry.getKey()).append(":\n");
                        for (Map.Entry<String, CanonicalType> fieldEntry : visibleFields) {
                            renderField(sb, variantPath + "." + fieldEntry.getKey(), fieldEntry.getValue(),
                                    true, indent + "    ", synonyms, excludedFieldPaths);
                        }
                    }
                }
            }

            case RecordType r -> {
                for (Map.Entry<String, CanonicalType> entry : r.fields().entrySet()) {
                    String fieldPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                    renderField(sb, fieldPath, entry.getValue(), true, indent, synonyms, excludedFieldPaths);
                }
            }
        }
    }

    private void appendSynonyms(StringBuilder sb, String indent, String path, Map<String, List<String>> synonyms) {
        List<String> syns = synonyms.get(path);
        if (syns != null && !syns.isEmpty()) {
            sb.append(indent).append("  synonyms: ").append(String.join(", ", syns)).append("\n");
        }
    }

    private String primitiveName(PrimitiveType p) {
        return switch (p.kind()) {
            case STRING -> "String";
            case NUMBER -> "Number";
            case DATE -> "Date";
            case BOOLEAN -> "Boolean";
        };
    }
}
