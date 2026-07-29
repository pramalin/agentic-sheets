package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalValue;

import java.util.List;

/**
 * Every row's outcome from validating a whole batch against the ADT.
 * Rows that fail are reported, not silently dropped -- {@code
 * validRows} dispatches; {@code rowErrors} is returned to whoever
 * triggered the approval, and would be what a human reviewer sees in
 * Step 8's UI once it exists.
 */
public record ValidationReport(List<CanonicalValue> validRows, List<RowError> rowErrors) {

    public record RowError(int rowIndex, List<String> problems) {
    }

    public boolean hasErrors() {
        return !rowErrors.isEmpty();
    }
}
