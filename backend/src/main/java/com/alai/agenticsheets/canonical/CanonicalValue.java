package com.alai.agenticsheets.canonical;

/**
 * An actual value constructed against a {@link CanonicalType} -- where
 * {@code CanonicalType} describes the shape a team's canonical model
 * takes, these are the runtime instances {@link
 * com.alai.agenticsheets.mapping.CanonicalRowBuilder} constructs from one
 * source row plus an approved mapping. This is Step 7's real enforcement
 * point: Step 6's {@code MappingProposalStructuralValidator} checked the
 * agent's *proposal* was structurally sane; this checks that applying it
 * to actual row data produces a value that actually satisfies the ADT.
 */
public sealed interface CanonicalValue
        permits StringValue, NumberValue, DateValue, BooleanValue, RecordValue, VariantValue, AbsentValue {
}
