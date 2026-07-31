package com.alai.agenticsheets.inbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InboxFilenameParserTest {

    private final InboxFilenameParser parser = new InboxFilenameParser();

    @Test
    void parsesASimpleThreeSegmentFilename() {
        var parsed = parser.parse("holdings_jpmc_20260115.xlsx").orElseThrow();
        assertThat(parsed.feedType()).isEqualTo("holdings");
        assertThat(parsed.clientId()).isEqualTo("jpmc");
        assertThat(parsed.sourceDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void parsesAMultiWordFeedTypeFromTheRight() {
        // The real fixture this project's own review round caught a
        // fixed-count split() would have broken on.
        var parsed = parser.parse("rate_reset_pimco_20250501.xlsx").orElseThrow();
        assertThat(parsed.feedType()).isEqualTo("rate_reset");
        assertThat(parsed.clientId()).isEqualTo("pimco");
        assertThat(parsed.sourceDate()).isEqualTo(LocalDate.of(2025, 5, 1));
    }

    @Test
    void rejectsAFilenameWithNoExtension() {
        assertThat(parser.parse("holdings_jpmc_20260115")).isEmpty();
    }

    @Test
    void rejectsAFilenameWithTooFewSegments() {
        assertThat(parser.parse("jpmc_20260115.xlsx")).isEmpty();
    }

    @Test
    void rejectsAnInvalidCalendarDate() {
        // Strict resolution -- 20260231 (Feb 31st) doesn't exist.
        assertThat(parser.parse("holdings_jpmc_20260231.xlsx")).isEmpty();
    }

    @Test
    void rejectsANonNumericDateToken() {
        assertThat(parser.parse("holdings_jpmc_notadate.xlsx")).isEmpty();
    }

    @Test
    void rejectsAFilenameThatIsJustAnExtension() {
        assertThat(parser.parse(".xlsx")).isEmpty();
    }
}
