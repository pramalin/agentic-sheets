package com.alai.agenticsheets.spreadsheet;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises this project's own glue logic -- extracting text content from
 * a tool result and parsing it as JSON, surfacing an MCP-level error
 * result as an exception -- against a mocked {@link McpSyncClient}. Not
 * an integration test: no live sheets-reader-mcp involved, and doesn't
 * need one to verify this class's own behavior is correct.
 */
class SpreadsheetExplorerServiceTest {

    @Test
    void describeTableParsesTheToolResultAsJson() {
        McpSyncClient client = mock(McpSyncClient.class);
        String json = """
                {"worksheet":"Claims","headerRowIndex":0,"columns":[{"header":"Claim ID"}]}""";
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
        when(client.callTool(any())).thenReturn(result);

        SpreadsheetExplorerService service = new SpreadsheetExplorerService(List.of(client), JsonMapper.builder().build());
        JsonNode node = service.describeTable("claims_q1.xlsx", "Claims");

        assertThat(node.get("worksheet").asText()).isEqualTo("Claims");
        assertThat(node.get("columns").get(0).get("header").asText()).isEqualTo("Claim ID");
    }

    @Test
    void readRowsParsesTheToolResultAsJson() {
        McpSyncClient client = mock(McpSyncClient.class);
        String json = """
                {"returnedRowCount":2,"hasMore":true,"rows":[{"Claim ID":"CLM-1001"}]}""";
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
        when(client.callTool(any())).thenReturn(result);

        SpreadsheetExplorerService service = new SpreadsheetExplorerService(List.of(client), JsonMapper.builder().build());
        JsonNode node = service.readRows("claims_q1.xlsx", "Claims", 0, 2);

        assertThat(node.get("returnedRowCount").asInt()).isEqualTo(2);
        assertThat(node.get("hasMore").asBoolean()).isTrue();
    }

    @Test
    void surfacesAnMcpErrorResultAsAnException() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult errorResult = McpSchema.CallToolResult.builder()
                .addTextContent("No such worksheet: DoesNotExist")
                .isError(true)
                .build();
        when(client.callTool(any())).thenReturn(errorResult);

        SpreadsheetExplorerService service = new SpreadsheetExplorerService(List.of(client), JsonMapper.builder().build());

        assertThatThrownBy(() -> service.describeTable("claims_q1.xlsx", "DoesNotExist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("error result");
    }

    @Test
    void rejectsNonJsonTextContent() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent("this is not json")
                .isError(false)
                .build();
        when(client.callTool(any())).thenReturn(result);

        SpreadsheetExplorerService service = new SpreadsheetExplorerService(List.of(client), JsonMapper.builder().build());

        assertThatThrownBy(() -> service.listWorksheets("claims_q1.xlsx"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-JSON");
    }

    @Test
    void constructorRejectsNoConfiguredClients() {
        assertThatThrownBy(() -> new SpreadsheetExplorerService(List.of(), JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No MCP clients configured");
    }
}
