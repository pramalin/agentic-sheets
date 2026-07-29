package com.alai.agenticsheets.spreadsheet;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Manual verification surface for Step 5 -- confirms the MCP client
 * wiring to sheets-reader-mcp actually works before any mapping logic
 * (Step 6) depends on it. {@code path} values are relative to
 * sheets-reader-mcp's own mounted workspace (this project's
 * {@code sample-input/} directory, per compose.yaml).
 */
@RestController
@RequestMapping("/internal/explore")
public class SpreadsheetExplorerController {

    private final SpreadsheetExplorerService explorer;

    public SpreadsheetExplorerController(SpreadsheetExplorerService explorer) {
        this.explorer = explorer;
    }

    @GetMapping("/tools")
    public List<String> tools() {
        return explorer.listToolNames();
    }

    @GetMapping("/worksheets")
    public JsonNode worksheets(@RequestParam String path) {
        return explorer.listWorksheets(path);
    }

    @GetMapping("/table")
    public JsonNode table(@RequestParam String path, @RequestParam String worksheet) {
        return explorer.describeTable(path, worksheet);
    }

    @GetMapping("/rows")
    public JsonNode rows(
            @RequestParam String path,
            @RequestParam String worksheet,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return explorer.readRows(path, worksheet, offset, limit);
    }
}
