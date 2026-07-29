package com.alai.agenticsheets;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/**
 * Step 2's whole job was "does the application context start." Step 4
 * added a real datasource; Step 5 added a real MCP client -- both need a
 * live dependency reachable at context-startup time (Spring AI's MCP
 * client autoconfiguration calls {@code .initialize()} synchronously as
 * part of bean creation, not lazily on first use), and neither Postgres
 * nor sheets-mcp exist during a plain `mvn test` run (only inside
 * `docker compose up`, where the real integration check already happens
 * and already passed).
 *
 * Two earlier attempts at solving this by *excluding* the spreadsheet
 * package from this test's component scan didn't work -- neither an
 * explicit {@code @ComponentScan(excludeFilters=...)} alongside
 * {@code @SpringBootApplication}, nor the same thing decomposed into
 * {@code @SpringBootConfiguration} + {@code @EnableAutoConfiguration} +
 * one {@code @ComponentScan}, actually kept
 * {@code SpreadsheetExplorerController}/{@code Service} out of the
 * context (confirmed by the real failure: our own constructor's "No MCP
 * clients configured" guard, not a Spring-level wiring error). Rather
 * than keep chasing exactly why component-scan exclusion wasn't taking,
 * this takes the simpler path: don't try to keep those beans out at all,
 * just give {@code SpreadsheetExplorerService}'s constructor a non-empty
 * client list -- one plain Mockito mock, registered as a
 * {@code @TestConfiguration} bean. The mock never has its methods
 * stubbed or called; this test only checks the context wires together,
 * not that any tool call succeeds -- {@code SpreadsheetExplorerServiceTest}
 * already covers that behavior with proper mocking, and Step 5's
 * `/internal/explore/*` checks already covered it live.
 *
 * Step 6 adds a similar-shaped risk: {@code spring.ai.openai.api-key}
 * defaults to blank (see application.yml), and some Spring AI
 * autoconfiguration validates that eagerly at bean-creation time even
 * though no network call happens until a chat request is actually made.
 * A placeholder value here sidesteps that without needing to know (and
 * possibly guess wrong, as happened twice already this session with
 * component-scan exclusion) which autoconfiguration class would need
 * excluding instead.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration,"
                + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration",
        "spring.ai.openai.api-key=sk-test-placeholder-never-called"
})
class AgenticSheetsApplicationTests {

    @TestConfiguration
    static class MockMcpClientConfig {
        @Bean
        McpSyncClient mockSheetsReaderMcpSyncClient() {
            return mock(McpSyncClient.class);
        }
    }

    @Test
    void contextLoads() {
    }
}
