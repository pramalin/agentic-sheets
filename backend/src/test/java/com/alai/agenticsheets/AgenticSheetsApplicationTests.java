package com.alai.agenticsheets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Step 2's whole job was "does the application context start" -- Step 4
 * adds a real datasource, which this particular smoke test deliberately
 * excludes: there's no live Postgres during a plain `mvn test` run (only
 * inside `docker compose up`, where the real integration check happens),
 * and this test's only job is confirming the rest of the context -- the
 * canonical registry, the controllers -- starts independent of the DB.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration"
})
class AgenticSheetsApplicationTests {

    @Test
    void contextLoads() {
    }
}
