package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidationRunRepository} deserializes {@code row_errors} back
 * from JSONB using a {@code JavaType} built from
 * {@code getTypeFactory().constructCollectionType(...)} -- this tests
 * that exact round-trip in isolation, since the repository method
 * itself needs a live Postgres to test properly (deferred, consistent
 * with this project's established pattern), but the JSON
 * serialize/deserialize logic doesn't.
 */
class ValidationRunRowErrorSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void roundTripsRowErrorsThroughJson() {
        List<ValidationReport.RowError> original = List.of(
                new ValidationReport.RowError(0, List.of("quantity is not a valid number")),
                new ValidationReport.RowError(2, List.of("required field 'account_id' has no mapping", "another problem")));

        String json = jsonMapper.writeValueAsString(original);

        JavaType listType = jsonMapper.getTypeFactory()
                .constructCollectionType(List.class, ValidationReport.RowError.class);
        List<ValidationReport.RowError> roundTripped = jsonMapper.readValue(json, listType);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void roundTripsAnEmptyListCorrectly() {
        List<ValidationReport.RowError> original = List.of();
        String json = jsonMapper.writeValueAsString(original);

        JavaType listType = jsonMapper.getTypeFactory()
                .constructCollectionType(List.class, ValidationReport.RowError.class);
        List<ValidationReport.RowError> roundTripped = jsonMapper.readValue(json, listType);

        assertThat(roundTripped).isEmpty();
    }
}
