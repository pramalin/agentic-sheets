package com.alai.agenticsheets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Step 2: a health endpoint and nothing else. Later steps add, in order:
 * a datasource and orchestration schema (Step 4), an MCP client wired to
 * sheets-reader-mcp (Step 5), mapping inference (Step 6), and a
 * deterministic validator + dispatcher (Step 7) -- see the repo README's
 * roadmap for the full sequence.
 */
@SpringBootApplication
public class AgenticSheetsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticSheetsApplication.class, args);
    }
}
