package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnFingerprintTest {

    private final ColumnFingerprint fingerprint = new ColumnFingerprint();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String hashOf(String columnsJson) {
        return fingerprint.hash(jsonMapper.readTree("{\"columns\": " + columnsJson + "}"));
    }

    @Test
    void sameColumnsSameOrderProduceTheSameHash() {
        String json = """
                [{"header": "Account", "inferredType": "string"}, {"header": "Quantity", "inferredType": "number"}]
                """;
        assertThat(hashOf(json)).isEqualTo(hashOf(json));
    }

    @Test
    void columnOrderDoesNotAffectTheHash() {
        String orderA = """
                [{"header": "Account", "inferredType": "string"}, {"header": "Quantity", "inferredType": "number"}]
                """;
        String orderB = """
                [{"header": "Quantity", "inferredType": "number"}, {"header": "Account", "inferredType": "string"}]
                """;
        assertThat(hashOf(orderA)).isEqualTo(hashOf(orderB));
    }

    @Test
    void aDifferentColumnSetProducesADifferentHash() {
        String withCustodian = """
                [{"header": "Account", "inferredType": "string"}, {"header": "Custodian", "inferredType": "string"}]
                """;
        String withoutCustodian = """
                [{"header": "Account", "inferredType": "string"}]
                """;
        assertThat(hashOf(withCustodian)).isNotEqualTo(hashOf(withoutCustodian));
    }

    @Test
    void aChangedInferredTypeProducesADifferentHash() {
        // Same header, different type -- e.g. a column that used to
        // read as text now reads as a native date. A real structural
        // change worth invalidating a remembered mapping over.
        String asString = """
                [{"header": "As Of Date", "inferredType": "string"}]
                """;
        String asDate = """
                [{"header": "As Of Date", "inferredType": "date"}]
                """;
        assertThat(hashOf(asString)).isNotEqualTo(hashOf(asDate));
    }

    @Test
    void duplicateHeadersArePreservedNotCollapsed() {
        // A plain Set would silently collapse these into one entry --
        // extract() returns a List specifically so this genuinely
        // differs from a single-column file.
        String duplicated = """
                [{"header": "Account", "inferredType": "string"}, {"header": "Account", "inferredType": "string"}]
                """;
        String single = """
                [{"header": "Account", "inferredType": "string"}]
                """;
        assertThat(hashOf(duplicated)).isNotEqualTo(hashOf(single));
    }

    @Test
    void missingInferredTypeIsTreatedAsAnEmptyStringNotAnError() {
        String json = """
                [{"header": "Account"}]
                """;
        assertThat(hashOf(json)).isNotBlank();
    }

    @Test
    void anEmptyColumnListProducesAStableEmptyFingerprint() {
        assertThat(hashOf("[]")).isEqualTo(hashOf("[]"));
    }
}
