package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches every row of an approved proposal's source file (paginated,
 * since {@code read_rows} caps at 500 per call -- see
 * {@code sheets-reader-mcp}) and validates each against the ADT via
 * {@link CanonicalRowBuilder}. Deterministic, no LLM involved -- this is
 * a separate concern from {@link Dispatcher}, which only decides what to
 * do with rows that already passed here.
 */
@Service
public class ProposalValidationService {

    private final SpreadsheetExplorerService explorer;
    private final CanonicalRowBuilder rowBuilder;
    private final JsonMapper jsonMapper;

    private static final int PAGE_SIZE = 500;

    public ProposalValidationService(SpreadsheetExplorerService explorer, CanonicalRowBuilder rowBuilder,
            JsonMapper jsonMapper) {
        this.explorer = explorer;
        this.rowBuilder = rowBuilder;
        this.jsonMapper = jsonMapper;
    }

    public ValidationReport validate(CanonicalModel model, ClientConfig client, ImportBatch batch,
            MappingProposal proposal) {
        Map<String, MappingProposal.FieldMapping> mappingsByPath = proposal.fieldMappings().stream()
                .collect(Collectors.toMap(MappingProposal.FieldMapping::canonicalFieldPath, fm -> fm));

        // client_id is deliberately never in the agent's own proposal (see
        // MappingProposalService's system prompt and mapping-notes.md) --
        // it's already known with certainty from the client parameter, not
        // something the agent should guess at. Without this, every row
        // would fail validation on a required field the proposal was
        // explicitly told to omit.
        mappingsByPath.put("client_id",
                new MappingProposal.FieldMapping("client_id", null, client.clientId(), null, null, null, 1.0,
                        "resolved externally, not from the agent's proposal"));

        List<CanonicalValue> validRows = new ArrayList<>();
        List<ValidationReport.RowError> rowErrors = new ArrayList<>();

        int rowIndex = 0;
        for (Map<String, String> row : fetchAllRows(batch.sourceFilename(), batch.worksheet())) {
            CanonicalRowBuilder.Result result = rowBuilder.build(model.root(), mappingsByPath, client, row);
            if (result.isValid()) {
                validRows.add(result.value());
            } else {
                rowErrors.add(new ValidationReport.RowError(rowIndex, result.errors()));
            }
            rowIndex++;
        }

        return new ValidationReport(validRows, rowErrors);
    }

    private List<Map<String, String>> fetchAllRows(String path, String worksheet) {
        List<Map<String, String>> allRows = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = explorer.readRows(path, worksheet, offset, PAGE_SIZE);
            JsonNode rowsNode = page.get("rows");
            if (rowsNode != null && rowsNode.isArray()) {
                for (JsonNode rowNode : rowsNode) {
                    allRows.add(toStringMap(rowNode));
                }
            }
            boolean hasMore = page.get("hasMore") != null && page.get("hasMore").asBoolean();
            if (!hasMore) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return allRows;
    }

    private Map<String, String> toStringMap(JsonNode rowNode) {
        Map<String, Object> raw = jsonMapper.convertValue(rowNode, Map.class);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toString());
        }
        return result;
    }
}
