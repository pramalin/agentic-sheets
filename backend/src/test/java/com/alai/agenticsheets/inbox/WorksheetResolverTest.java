package com.alai.agenticsheets.inbox;

import com.alai.agenticsheets.canonical.FeedRoute;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JSON in these tests is the real, confirmed {@code list_worksheets}
 * response shape -- {@code [{"name": ..., "rowCount": ..., "columnCount":
 * ..., "hidden": ...}]} -- obtained from a live call against this
 * project's own JPMC Holdings fixture, not invented.
 */
class WorksheetResolverTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private WorksheetResolver resolverReturning(String json) {
        SpreadsheetExplorerService explorer = mock(SpreadsheetExplorerService.class);
        when(explorer.listWorksheets("some/path.xlsx")).thenReturn(jsonMapper.readTree(json));
        return new WorksheetResolver(explorer);
    }

    @Test
    void resolvesTheSingleMatchingVisibleWorksheet() {
        WorksheetResolver resolver = resolverReturning("""
                [{"name": "Holdings", "rowCount": 5, "columnCount": 11, "hidden": false}]
                """);
        FeedRoute route = new FeedRoute("holdings", "Holdings", List.of("Holdings"));

        assertThat(resolver.resolve("some/path.xlsx", route)).isEqualTo("Holdings");
    }

    @Test
    void matchesAnyOfSeveralCandidateNames() {
        WorksheetResolver resolver = resolverReturning("""
                [{"name": "Rate Reset", "rowCount": 3, "columnCount": 7, "hidden": false}]
                """);
        FeedRoute route = new FeedRoute("rate_reset", "MarketRateBookValue", List.of("RateReset", "Rate Reset"));

        assertThat(resolver.resolve("some/path.xlsx", route)).isEqualTo("Rate Reset");
    }

    @Test
    void excludesHiddenWorksheetsFromMatching() {
        WorksheetResolver resolver = resolverReturning("""
                [
                  {"name": "Holdings", "rowCount": 0, "columnCount": 0, "hidden": true},
                  {"name": "Summary", "rowCount": 2, "columnCount": 3, "hidden": false}
                ]
                """);
        FeedRoute route = new FeedRoute("holdings", "Holdings", List.of("Holdings"));

        // "Holdings" exists in the workbook but is hidden -- no visible
        // match, so this should fail deterministically, not silently
        // resolve to the hidden sheet.
        assertThatThrownBy(() -> resolver.resolve("some/path.xlsx", route))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no worksheet matching");
    }

    @Test
    void failsDeterministicallyOnZeroMatches() {
        WorksheetResolver resolver = resolverReturning("""
                [{"name": "SomethingElse", "rowCount": 1, "columnCount": 1, "hidden": false}]
                """);
        FeedRoute route = new FeedRoute("holdings", "Holdings", List.of("Holdings"));

        assertThatThrownBy(() -> resolver.resolve("some/path.xlsx", route))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no worksheet matching");
    }

    @Test
    void failsDeterministicallyOnMultipleMatchesRatherThanGuessing() {
        WorksheetResolver resolver = resolverReturning("""
                [
                  {"name": "Holdings", "rowCount": 5, "columnCount": 11, "hidden": false},
                  {"name": "Holdings Backup", "rowCount": 5, "columnCount": 11, "hidden": false}
                ]
                """);
        FeedRoute route = new FeedRoute("holdings", "Holdings", List.of("Holdings", "Holdings Backup"));

        assertThatThrownBy(() -> resolver.resolve("some/path.xlsx", route))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
    }
}
