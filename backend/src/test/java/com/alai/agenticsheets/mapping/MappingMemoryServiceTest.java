package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.AbsentValue;
import com.alai.agenticsheets.canonical.CanonicalModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingMemoryServiceTest {

    private final MappingMemoryRepository mappingMemoryRepository = mock(MappingMemoryRepository.class);
    private final MappingMemoryService mappingMemoryService = new MappingMemoryService(mappingMemoryRepository);

    private final CanonicalModel model = new CanonicalModel("Holdings", 3, null, null, Map.of(), null);
    private final ImportBatch batch =
            new ImportBatch(99L, "Holdings", "jpmc", "inbox/f.xlsx", "hash", "Holdings", 3, "APPROVED");

    private MappingProposal.FieldMapping safeMapping() {
        return new MappingProposal.FieldMapping("account_id", "Account", null, null, null, List.of(), 0.9, null);
    }

    private StoredMappingProposal storedProposal(String origin, Long mappingMemoryId, String columnFingerprint,
            String clientConfigFingerprint) {
        MappingProposal proposal = new MappingProposal(List.of(safeMapping()), List.of(), "summary");
        return new StoredMappingProposal(1L, batch.id(), 3, proposal, "APPROVED", null,
                origin, mappingMemoryId, columnFingerprint, clientConfigFingerprint);
    }

    @Test
    void promotesACleanlyValidatedEligibleProposal() {
        StoredMappingProposal stored = storedProposal(ResolvedProposal.ORIGIN_AGENT, null, "col-hash", "cfg-hash");
        ValidationReport zeroErrors = new ValidationReport(List.of(new AbsentValue()), List.of());
        when(mappingMemoryRepository.promote(anyString(), anyString(), anyString(), eq(3), anyString(), anyString(),
                eq(stored.proposal()), eq(stored.id()))).thenReturn(Optional.of(55L));

        mappingMemoryService.promoteIfEligible(stored, batch, model, zeroErrors);

        verify(mappingMemoryRepository).promote("jpmc", "Holdings", "Holdings", 3, "cfg-hash", "col-hash",
                stored.proposal(), stored.id());
    }

    @Test
    void doesNotPromoteWhenValidationHasAnyRowErrors() {
        // The whole point of this class -- status == APPROVED (or even
        // DELIVERED) is not sufficient; a batch can reach either with
        // some rows valid and others erroring. Only zero errors counts.
        StoredMappingProposal stored = storedProposal(ResolvedProposal.ORIGIN_AGENT, null, "col-hash", "cfg-hash");
        ValidationReport hasErrors = new ValidationReport(
                List.of(new AbsentValue()), List.of(new ValidationReport.RowError(2, List.of("bad value"))));

        mappingMemoryService.promoteIfEligible(stored, batch, model, hasErrors);

        verify(mappingMemoryRepository, never()).promote(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                any(MappingProposal.class), anyLong());
    }

    @Test
    void doesNotPromoteAnIneligibleProposalEvenWithZeroErrors() {
        MappingProposal.FieldMapping constantMapping =
                new MappingProposal.FieldMapping("as_of_date", null, "2026-02-01", null, null, List.of(), 0.7, null);
        MappingProposal ineligible = new MappingProposal(List.of(constantMapping), List.of(), "summary");
        StoredMappingProposal stored = new StoredMappingProposal(1L, batch.id(), 3, ineligible, "APPROVED", null,
                ResolvedProposal.ORIGIN_AGENT, null, "col-hash", "cfg-hash");
        ValidationReport zeroErrors = new ValidationReport(List.of(new AbsentValue()), List.of());

        mappingMemoryService.promoteIfEligible(stored, batch, model, zeroErrors);

        verify(mappingMemoryRepository, never()).promote(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                any(MappingProposal.class), anyLong());
    }

    @Test
    void doesNotPromoteWhenFingerprintsAreMissing() {
        // Only possible for a row predating these columns existing --
        // nothing to key a memory entry on.
        StoredMappingProposal stored = storedProposal(ResolvedProposal.ORIGIN_AGENT, null, null, null);
        ValidationReport zeroErrors = new ValidationReport(List.of(new AbsentValue()), List.of());

        mappingMemoryService.promoteIfEligible(stored, batch, model, zeroErrors);

        verify(mappingMemoryRepository, never()).promote(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                any(MappingProposal.class), anyLong());
    }

    @Test
    void invalidatesAMemoryDerivedProposalOnRejection() {
        StoredMappingProposal stored = storedProposal(ResolvedProposal.ORIGIN_MEMORY, 42L, "col-hash", "cfg-hash");

        mappingMemoryService.invalidateIfMemoryDerived(stored, "rejected: wrong mapping");

        verify(mappingMemoryRepository).invalidate(42L, "rejected: wrong mapping");
    }

    @Test
    void doesNotInvalidateAnAgentOriginatedProposalOnRejection() {
        StoredMappingProposal stored = storedProposal(ResolvedProposal.ORIGIN_AGENT, null, "col-hash", "cfg-hash");

        mappingMemoryService.invalidateIfMemoryDerived(stored, "rejected: wrong mapping");

        verify(mappingMemoryRepository, never()).invalidate(anyLong(), anyString());
    }

    @Test
    void doesNotInvalidateAHumanAmendedProposalOnRejection() {
        StoredMappingProposal stored =
                storedProposal(ResolvedProposal.ORIGIN_HUMAN_AMENDMENT, null, "col-hash", "cfg-hash");

        mappingMemoryService.invalidateIfMemoryDerived(stored, "rejected: wrong mapping");

        verify(mappingMemoryRepository, never()).invalidate(anyLong(), anyString());
    }
}
