package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches every row of an approved proposal's source file (paginated via
 * {@link SpreadsheetRowReader}, since {@code read_rows} caps at 500 per
 * call -- see {@code sheets-reader-mcp}) and validates each against the
 * ADT via {@link CanonicalRowBuilder}. Deterministic, no LLM involved --
 * this is a separate concern from {@link Dispatcher}, which only decides
 * what to do with rows that already passed here.
 *
 * <p>As of the Local LLM phase's Step LLM-1 (see
 * {@code docs/local-llm-enhancements.md}), the paginated row-fetching
 * logic previously private to this class lives in {@link
 * SpreadsheetRowReader} instead, so it can be reused by other
 * deterministic mapping logic without duplicating the paging loop. No
 * behavior change here -- same page size, same row shape, same error
 * propagation.
 */
@Service
public class ProposalValidationService {

    private final SpreadsheetRowReader rowReader;
    private final CanonicalRowBuilder rowBuilder;

    public ProposalValidationService(SpreadsheetRowReader rowReader, CanonicalRowBuilder rowBuilder) {
        this.rowReader = rowReader;
        this.rowBuilder = rowBuilder;
    }

    public ValidationReport validate(CanonicalModel model, ClientConfig client, ImportBatch batch,
            MappingProposal proposal) {
        Map<String, MappingProposal.FieldMapping> mappingsByPath = proposal.fieldMappings().stream()
                .collect(Collectors.toMap(MappingProposal.FieldMapping::canonicalFieldPath, fm -> fm));

        // client_id is deliberately never in the agent's own proposal (see
        // AgentMappingProposalService's system prompt and mapping-notes.md) --
        // it's already known with certainty from the client parameter, not
        // something the agent should guess at. Without this, every row
        // would fail validation on a required field the proposal was
        // explicitly told to omit.
        //
        // transformations: List.of(), not null -- the exact same bug,
        // same round, as FieldAliasResolver's own deterministic entries
        // (see that file's own comment for the full account). This one
        // is easy to miss precisely because it rarely reaches a real
        // browser: this synthesized entry gets folded into
        // `mappingsByPath` for *validation* purposes here, but the
        // actual value shown to a reviewer comes from whatever
        // AgentMappingProposalService merged into the proposal earlier
        // -- so this specific null wouldn't itself have caused the
        // crash that was actually observed. Fixed anyway, for the same
        // reason: consistency with the same non-nullable frontend
        // contract, and because a future caller of this method that
        // *does* surface this exact object to the UI shouldn't inherit
        // a null that's already been named as a known defect once.
        mappingsByPath.put("client_id",
                new MappingProposal.FieldMapping("client_id", null, client.clientId(), null, null, List.of(), 1.0,
                        "resolved externally, not from the agent's proposal"));

        List<CanonicalValue> validRows = new ArrayList<>();
        List<ValidationReport.RowError> rowErrors = new ArrayList<>();

        int rowIndex = 0;
        for (Map<String, String> row : rowReader.readAll(batch.sourceFilename(), batch.worksheet())) {
            CanonicalRowBuilder.Result result = rowBuilder.build(model.root(), mappingsByPath, client, row);
            if (result.isValid()) {
                validRows.add(result.value());
            } else {
                rowErrors.add(new ValidationReport.RowError(rowIndex, result.errors()));
            }
            rowIndex++;
        }

        return new ValidationReport(validRows, rowErrors);
    }
}
