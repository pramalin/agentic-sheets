package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Step 6: proposes a mapping from a source spreadsheet's columns onto a
 * canonical model's ADT, using a chat model with structured output bound
 * directly to {@link MappingProposal}. Deliberately does nothing else --
 * no validation against the ADT, no persistence, no delivery. Those are
 * separate concerns (Step 7's deterministic validator, this package's
 * repositories, Step 7's dispatcher) precisely so the one piece that
 * involves an LLM stays as small and inspectable as possible.
 */
@Service
public class MappingProposalService {

    private final ChatClient chatClient;
    private final CanonicalModelRegistry registry;
    private final CanonicalModelPromptRenderer renderer;
    private final SpreadsheetExplorerService explorer;

    public MappingProposalService(
            ChatClient.Builder chatClientBuilder,
            CanonicalModelRegistry registry,
            CanonicalModelPromptRenderer renderer,
            SpreadsheetExplorerService explorer) {
        this.chatClient = chatClientBuilder.build();
        this.registry = registry;
        this.renderer = renderer;
        this.explorer = explorer;
    }

    public MappingProposal propose(String modelId, String clientId, String sourcePath, String worksheet) {
        CanonicalModel model = registry.get(modelId);
        ClientConfig client = registry.getClient(clientId);
        JsonNode table = explorer.describeTable(sourcePath, worksheet);

        String systemPrompt = """
                You map a client's raw spreadsheet columns onto a fixed canonical
                data model. The canonical model below is an Algebraic Data Type --
                product types (records: every field present) and sum types (tagged
                variants: exactly one present). For a sum type field, name which
                variant applies using its variant-qualified path for any of that
                variant's own fields (e.g. asset_class.FixedIncome.maturity_date).

                The client this file belongs to is already known with certainty --
                it's given below, not something for you to infer. Any canonical
                field literally named client_id (or ending in .client_id) is
                already resolved outside this mapping; do not propose a mapping
                for it at all, don't include it in fieldMappings.

                A sum type field's variant can be determined two different ways --
                pick whichever actually applies, don't default to one:
                  - selectedVariant: every row in this file is the same fixed
                    variant (e.g. a whole file of only fixed-income positions).
                  - variantValueMap: the variant varies per row based on that row's
                    own data -- map each distinct source value you observe to the
                    canonical variant name it corresponds to (e.g. "Equity" ->
                    "Equity", "Fixed Income" -> "FixedIncome"). This is the common
                    case for a column whose values differ row to row.
                Set exactly one of the two, never both, and never leave a sum type
                field's variant unresolved just because it's data-dependent.

                A source column with no reasonable canonical home is not an error --
                list it as unmapped rather than forcing a mapping.

                Some values come from a banner row or other free text above the real
                header, not a per-row column -- use sourceConstant for those, not
                sourceColumn, and give them a lower confidence than a direct
                column-name match, since extracting a value from free text is a
                different (and less certain) kind of inference than matching a
                header.
                """;

        String userPrompt = renderer.render(model)
                + "\n\nClient '" + clientId + "' source-format conventions:\n"
                + "  date format: " + client.dateFormat() + "\n"
                + "\nSource table (from describe_table on '" + sourcePath + "', worksheet '" + worksheet + "'):\n"
                + table.toString();

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(MappingProposal.class);
    }
}
