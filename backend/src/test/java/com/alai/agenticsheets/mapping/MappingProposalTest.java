package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MappingProposal}'s compact constructor -- Local LLM phase,
 * Step LLM-6 (see {@code docs/local-llm-enhancements.md}). Traced back
 * to a real {@code NullPointerException} against actual Qwen 2.5 3B
 * output: a proposal whose {@code fieldMappings} decoded to {@code null}
 * rather than an empty list crashed {@link SumTypeMappingResolver}'s
 * first line with an unhandled exception that leaked to the HTTP
 * boundary as a raw 500, instead of the clean, reported validation
 * failure a malformed proposal should always produce.
 */
class MappingProposalTest {

    @Test
    void nullFieldMappingsIsNormalizedToAnEmptyList() {
        MappingProposal proposal = new MappingProposal(null, List.of(), "test");
        assertThat(proposal.fieldMappings()).isNotNull().isEmpty();
    }

    @Test
    void nullUnmappedSourceColumnsIsNormalizedToAnEmptyList() {
        MappingProposal proposal = new MappingProposal(List.of(), null, "test");
        assertThat(proposal.unmappedSourceColumns()).isNotNull().isEmpty();
    }

    @Test
    void bothNullNormalizedIndependently() {
        MappingProposal proposal = new MappingProposal(null, null, "malformed model output");
        assertThat(proposal.fieldMappings()).isEmpty();
        assertThat(proposal.unmappedSourceColumns()).isEmpty();
        assertThat(proposal.summary()).isEqualTo("malformed model output");
    }

    @Test
    void nonNullListsArePassedThroughUnchanged() {
        MappingProposal.FieldMapping fm = new MappingProposal.FieldMapping(
                "account_id", "Account", null, null, null, null, 0.9, "note");
        MappingProposal proposal = new MappingProposal(List.of(fm), List.of("Extra Column"), "test");

        assertThat(proposal.fieldMappings()).containsExactly(fm);
        assertThat(proposal.unmappedSourceColumns()).containsExactly("Extra Column");
    }
}
