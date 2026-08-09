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
 */
@Component
public class CanonicalModelPromptRenderer {

    public String render(CanonicalModel model) {
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
        renderField(sb, "", model.root(), true, "", model.synonyms());
        return sb.toString();
    }

    private void renderField(StringBuilder sb, String path, CanonicalType type, boolean required,
            String indent, Map<String, List<String>> synonyms) {
        switch (type) {
            case OptionType o -> renderField(sb, path, o.inner(), false, indent, synonyms);

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
                    if (variant.fields().isEmpty()) {
                        sb.append(indent).append("  - variant ").append(entry.getKey())
                                .append(": no extra fields\n");
                    } else {
                        sb.append(indent).append("  - variant ").append(entry.getKey()).append(":\n");
                        for (Map.Entry<String, CanonicalType> fieldEntry : variant.fields().entrySet()) {
                            renderField(sb, variantPath + "." + fieldEntry.getKey(), fieldEntry.getValue(),
                                    true, indent + "    ", synonyms);
                        }
                    }
                }
            }

            case RecordType r -> {
                for (Map.Entry<String, CanonicalType> entry : r.fields().entrySet()) {
                    String fieldPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                    renderField(sb, fieldPath, entry.getValue(), true, indent, synonyms);
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
