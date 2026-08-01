package com.alai.agenticsheets.inbox;

import com.alai.agenticsheets.canonical.FeedRoute;
import com.alai.agenticsheets.spreadsheet.SpreadsheetExplorerService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves which of a workbook's actual worksheets a {@link FeedRoute}
 * meant, given its list of candidate names -- extracted out of
 * {@link InboxScanner} so this genuinely non-trivial matching logic
 * (hidden-sheet filtering, zero-match, multi-match) is directly
 * testable on its own, the same reasoning {@link InboxFilenameParser}
 * already got its own class for.
 *
 * Real response shape, confirmed via a live call, not guessed:
 * {@code list_worksheets} returns a plain JSON array of
 * {@code {name, rowCount, columnCount, hidden}} objects -- e.g.
 * {@code [{"name": "Holdings", "rowCount": 5, "columnCount": 11,
 * "hidden": false}]}. Hidden worksheets are deliberately excluded from
 * matching -- a hidden sheet is unlikely to be the intended data sheet
 * (more often a notes/lookup/pivot-source tab), and excluding it
 * reduces the chance of an accidental false match.
 *
 * {@code .asText()}/{@code .asBoolean()} are the exact method names
 * already proven working elsewhere in this codebase against this same
 * {@code tools.jackson.databind.JsonNode} type (see
 * {@code AgentMappingProposalService#extractColumnHeaders} and
 * {@code ProposalValidationService}'s own {@code hasMore} check) --
 * not assumed from Jackson 2.x naming conventions, which this
 * project's actual Jackson 3.x dependency doesn't always share
 * (confirmed the hard way once already elsewhere in this project:
 * {@code .asString()} would have been wrong here).
 */
@Component
public class WorksheetResolver {

    private final SpreadsheetExplorerService explorer;

    public WorksheetResolver(SpreadsheetExplorerService explorer) {
        this.explorer = explorer;
    }

    /**
     * @return the single visible worksheet name matching one of
     * {@code route.worksheetNames()}
     * @throws IllegalStateException if zero or more than one visible
     * worksheet matches -- a quarantine-worthy condition for the
     * caller, never a guess at which one was intended
     */
    public String resolve(String relativePath, FeedRoute route) {
        JsonNode worksheets = explorer.listWorksheets(relativePath);
        List<String> visibleNames = extractVisibleNames(worksheets);

        List<String> matches = visibleNames.stream()
                .filter(name -> route.worksheetNames().contains(name))
                .distinct()
                .toList();

        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "no worksheet matching " + route.worksheetNames() + " found among visible worksheets "
                            + visibleNames);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "ambiguous: multiple worksheets matched " + route.worksheetNames() + ": " + matches);
        }
        return matches.get(0);
    }

    List<String> extractVisibleNames(JsonNode worksheets) {
        if (!worksheets.isArray()) {
            throw new IllegalStateException(
                    "unexpected list_worksheets response shape (not an array): " + worksheets);
        }

        List<String> visibleNames = new ArrayList<>();
        for (JsonNode entry : worksheets) {
            JsonNode hiddenNode = entry.get("hidden");
            boolean hidden = hiddenNode != null && hiddenNode.asBoolean();
            if (hidden) {
                continue;
            }
            JsonNode nameNode = entry.get("name");
            if (nameNode != null) {
                visibleNames.add(nameNode.asText());
            }
        }
        return visibleNames;
    }
}
