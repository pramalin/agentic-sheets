package com.alai.agenticsheets.spreadsheet;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Step 5: proves the MCP client wiring to sheets-reader-mcp actually
 * works, with direct tool calls -- no LLM/agent reasoning involved. That
 * arrives in Step 6, once there's an actual mapping decision for a model
 * to make; "which tool to call, with which arguments" isn't one here (the
 * caller already knows), so there's no reason to depend on a chat model
 * or an API key just to prove the connection.
 *
 * Tool results come back as MCP text content holding a JSON string (see
 * sheets-reader-mcp's own {@code TableDescription}/{@code RowsPage}
 * records) -- parsed here into a {@link JsonNode} so callers get real
 * JSON, not a string callers would have to parse themselves again.
 */
@Service
public class SpreadsheetExplorerService {

    private final McpSyncClient sheetsReaderClient;
    private final JsonMapper jsonMapper;

    public SpreadsheetExplorerService(List<McpSyncClient> mcpSyncClients, JsonMapper jsonMapper) {
        if (mcpSyncClients.isEmpty()) {
            throw new IllegalStateException(
                    "No MCP clients configured -- check spring.ai.mcp.client.streamable-http.connections");
        }
        // Only one upstream MCP server for now; revisit (look up by name
        // instead of taking the first) once a second one is added.
        this.sheetsReaderClient = mcpSyncClients.get(0);
        this.jsonMapper = jsonMapper;
    }

    public List<String> listToolNames() {
        return sheetsReaderClient.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .toList();
    }

    public JsonNode listWorksheets(String path) {
        return callTool("list_worksheets", Map.of("path", path));
    }

    public JsonNode describeTable(String path, String worksheet) {
        return callTool("describe_table", Map.of("path", path, "worksheet", worksheet));
    }

    public JsonNode readRows(String path, String worksheet, int offset, int limit) {
        return callTool("read_rows", Map.of(
                "path", path, "worksheet", worksheet, "offset", offset, "limit", limit));
    }

    private JsonNode callTool(String name, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
        McpSchema.CallToolResult result = sheetsReaderClient.callTool(request);

        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("Tool '" + name + "' returned an error result: " + result.content());
        }

        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool '" + name + "' returned no text content"));

        try {
            return jsonMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Tool '" + name + "' returned non-JSON text: " + text, e);
        }
    }
}
