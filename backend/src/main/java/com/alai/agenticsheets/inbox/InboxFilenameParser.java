package com.alai.agenticsheets.inbox;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * Parses {@code <feedType>_<client>_<yyyyMMdd>.<ext>} -- Step 9's
 * roadmap convention -- from the right, not a fixed-count
 * {@code split("_")}. A real fixture in this project
 * (rate_reset_pimco_20250501.xlsx) is exactly why that would be wrong:
 * {@code feedType} is itself two words here, so a split expecting
 * exactly three segments breaks on it. Take the extension off, the
 * trailing token as the date, the token before that as the client, and
 * everything remaining as the feed type -- regardless of how many
 * underscores are in it.
 *
 * Returns {@link Optional#empty()} on anything that doesn't fit the
 * convention, deliberately not throwing -- an unparseable filename is
 * routine input for a directory the scanner doesn't control the
 * contents of, not an application error. The scanner quarantines these
 * rather than retrying them forever.
 */
@Component
public class InboxFilenameParser {

    // uuuu (plain year), not yyyy (year-of-era) -- confirmed by
    // actually running it, not assumed: under ResolverStyle.STRICT,
    // yyyy correctly parses the individual fields (year 2026, month 1,
    // day 15) but then fails to resolve them into a LocalDate at all,
    // since "year-of-era" is technically ambiguous without an explicit
    // era also being present. uuuu doesn't have that ambiguity and
    // still correctly rejects invalid calendar dates (e.g. Feb 31st).
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public record ParsedFilename(String feedType, String clientId, LocalDate sourceDate) {
    }

    public Optional<ParsedFilename> parse(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return Optional.empty();
        }
        String stem = filename.substring(0, dot);

        int lastUnderscore = stem.lastIndexOf('_');
        if (lastUnderscore <= 0 || lastUnderscore == stem.length() - 1) {
            return Optional.empty();
        }
        String dateToken = stem.substring(lastUnderscore + 1);
        String rest = stem.substring(0, lastUnderscore);

        int secondLastUnderscore = rest.lastIndexOf('_');
        if (secondLastUnderscore <= 0 || secondLastUnderscore == rest.length() - 1) {
            return Optional.empty();
        }
        String clientId = rest.substring(secondLastUnderscore + 1);
        String feedType = rest.substring(0, secondLastUnderscore);

        LocalDate sourceDate;
        try {
            sourceDate = LocalDate.parse(dateToken, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }

        return Optional.of(new ParsedFilename(feedType, clientId, sourceDate));
    }
}
