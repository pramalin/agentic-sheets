package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every row of a worksheet via the Sheets MCP {@code read_rows}
 * operation, paginating transparently -- {@code read_rows} caps at
 * {@value #PAGE_SIZE} rows per call (see {@code sheets-reader-mcp}), so a
 * larger worksheet needs multiple calls, followed via the response's
 * {@code hasMore} flag until it comes back {@code false}.
 *
 * <p>Extracted from {@link ProposalValidationService}'s original private
 * {@code fetchAllRows}/{@code toStringMap} methods (Local LLM phase, Step
 * LLM-1 -- see {@code docs/local-llm-enhancements.md}) so the same
 * paginated-read behavior can be reused by other deterministic mapping
 * logic -- specifically the planned sum-type resolver (Step LLM-2), which
 * needs every observed value in a source column, not just
 * {@code describe_table}'s sample values. This extraction is deliberately
 * behavior-preserving: same page size, same termination condition, same
 * row shape (an ordered {@code Map<String, String>} per row, {@code null}
 * cell values preserved as {@code null} rather than the literal string
 * {@code "null"}), same error propagation (an MCP tool error surfaces as
 * whatever {@link SpreadsheetExplorerService#readRows} itself throws --
 * currently an {@link IllegalStateException} -- unchanged by this class).
 */
@Service
public class SpreadsheetRowReader {

    private static final int PAGE_SIZE = 500;

    private final SpreadsheetExplorerService explorer;
    private final JsonMapper jsonMapper;

    public SpreadsheetRowReader(SpreadsheetExplorerService explorer, JsonMapper jsonMapper) {
        this.explorer = explorer;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Reads and returns every row of {@code worksheet} in the file at
     * {@code path}, aggregated across as many {@code read_rows} pages as
     * needed, in the order the rows were returned.
     */
    public List<Map<String, String>> readAll(String path, String worksheet) {
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
