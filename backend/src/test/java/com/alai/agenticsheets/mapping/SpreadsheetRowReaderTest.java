package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the pagination loop extracted from {@link
 * ProposalValidationService} (Local LLM phase, Step LLM-1 -- see {@code
 * docs/local-llm-enhancements.md}). Purely behavior-preserving: these
 * tests exercise exactly the paging/termination/error-propagation
 * behavior {@code ProposalValidationService.fetchAllRows} already had,
 * now against the extracted {@link SpreadsheetRowReader} directly.
 */
class SpreadsheetRowReaderTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode page(boolean hasMore, int rowCount, int startingAt) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                rows.append(",");
            }
            rows.append("{\"Account\": \"ACC-").append(startingAt + i).append("\"}");
        }
        String json = "{\"hasMore\": " + hasMore + ", \"rows\": [" + rows + "]}";
        return jsonMapper.readTree(json);
    }

    @Test
    void fewerThan500Rows_readInOneCall() {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        when(explorer.readRows("f.xlsx", "Holdings", 0, 500)).thenReturn(page(false, 3, 0));

        SpreadsheetRowReader reader = new SpreadsheetRowReader(explorer, jsonMapper);
        List<Map<String, String>> rows = reader.readAll("f.xlsx", "Holdings");

        assertThat(rows).hasSize(3);
        verify(explorer, times(1)).readRows("f.xlsx", "Holdings", 0, 500);
    }

    @Test
    void exactly500RowsWithNoFurtherPage_readInOneCall() {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        // Boundary case: a page can legitimately be exactly PAGE_SIZE long
        // and still be the last page -- hasMore, not row count, is what
        // actually decides whether another call happens.
        when(explorer.readRows("f.xlsx", "Holdings", 0, 500)).thenReturn(page(false, 500, 0));

        SpreadsheetRowReader reader = new SpreadsheetRowReader(explorer, jsonMapper);
        List<Map<String, String>> rows = reader.readAll("f.xlsx", "Holdings");

        assertThat(rows).hasSize(500);
        verify(explorer, times(1)).readRows("f.xlsx", "Holdings", 0, 500);
    }

    @Test
    void moreThan500Rows_readsAsSecondPage() {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        when(explorer.readRows("f.xlsx", "Holdings", 0, 500)).thenReturn(page(true, 500, 0));
        when(explorer.readRows("f.xlsx", "Holdings", 500, 500)).thenReturn(page(false, 10, 500));

        SpreadsheetRowReader reader = new SpreadsheetRowReader(explorer, jsonMapper);
        List<Map<String, String>> rows = reader.readAll("f.xlsx", "Holdings");

        assertThat(rows).hasSize(510);
        // Order preserved across pages: first page's rows first, in order,
        // then the second page's, in order.
        assertThat(rows.get(0).get("Account")).isEqualTo("ACC-0");
        assertThat(rows.get(499).get("Account")).isEqualTo("ACC-499");
        assertThat(rows.get(500).get("Account")).isEqualTo("ACC-500");
        assertThat(rows.get(509).get("Account")).isEqualTo("ACC-509");
        verify(explorer, times(1)).readRows("f.xlsx", "Holdings", 0, 500);
        verify(explorer, times(1)).readRows("f.xlsx", "Holdings", 500, 500);
    }

    @Test
    void zeroRows_emptyWorksheet_terminatesAfterOneCall() {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        // The boundary this case exists to guard: an empty first page
        // must still terminate via hasMore=false, not loop forever or
        // attempt a second call it has no reason to make.
        when(explorer.readRows("f.xlsx", "Holdings", 0, 500)).thenReturn(page(false, 0, 0));

        SpreadsheetRowReader reader = new SpreadsheetRowReader(explorer, jsonMapper);
        List<Map<String, String>> rows = reader.readAll("f.xlsx", "Holdings");

        assertThat(rows).isEmpty();
        verify(explorer, times(1)).readRows("f.xlsx", "Holdings", 0, 500);
    }

    @Test
    void propagatesReadFailureFromExplorer() {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        when(explorer.readRows("f.xlsx", "Holdings", 0, 500))
                .thenThrow(new IllegalStateException("Tool 'read_rows' returned an error result: boom"));

        SpreadsheetRowReader reader = new SpreadsheetRowReader(explorer, jsonMapper);

        assertThatThrownBy(() -> reader.readAll("f.xlsx", "Holdings"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }
}
