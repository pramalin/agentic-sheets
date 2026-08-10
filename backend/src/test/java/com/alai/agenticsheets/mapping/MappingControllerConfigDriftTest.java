package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.ClientConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Post-benchmark hardening (see {@code docs/local-llm-enhancements.md}'s
 * "review, client-config governance" section). An external review's own
 * high-severity catch: a pending proposal was never re-checked against
 * the client config's own current fingerprint before approval, and
 * {@code /amend} persisted an amended proposal under the OLD proposal's
 * stale fingerprint rather than the one it was actually just validated
 * against. Deliberately Mockito-only, not a full Testcontainers
 * integration test -- {@link MappingController} has too many
 * dependencies for a from-scratch integration harness to be worth
 * building in this round, and the actual transactional behavior of
 * {@link ProposalDecisionService#claimForApproval} is already covered
 * by {@link ProposalDecisionServiceTransactionalTest}; this test exists
 * only to prove the NEW pre-check this round added actually runs, and
 * runs before anything else.
 */
class MappingControllerConfigDriftTest {

    private static final MappingProposal DUMMY_PROPOSAL = new MappingProposal(java.util.List.of(), java.util.List.of(), null);

    private record Harness(
            MappingController controller,
            MappingProposalRepository mappingProposalRepository,
            ImportBatchRepository importBatchRepository,
            CanonicalModelRegistry registry,
            ProposalDecisionService proposalDecisionService,
            ClientConfigFingerprint clientConfigFingerprint,
            AgentMappingProposalService proposalService) {
    }

    private Harness harness() {
        MappingProposalRepository mappingProposalRepository = mock(MappingProposalRepository.class);
        ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
        CanonicalModelRegistry registry = mock(CanonicalModelRegistry.class);
        ProposalDecisionService proposalDecisionService = mock(ProposalDecisionService.class);
        ClientConfigFingerprint clientConfigFingerprint = mock(ClientConfigFingerprint.class);
        AgentMappingProposalService proposalService = mock(AgentMappingProposalService.class);

        MappingController controller = new MappingController(
                proposalService,
                mock(MappingWorkflowService.class),
                importBatchRepository,
                mappingProposalRepository,
                mock(ValidationRunRepository.class),
                mock(DeliveryLogRepository.class),
                mock(FileHasher.class),
                registry,
                mock(ProposalValidationService.class),
                mock(Dispatcher.class),
                proposalDecisionService,
                mock(MappingMemoryService.class),
                mock(ConventionSuggestionService.class),
                mock(ConventionSuggestionRepository.class),
                clientConfigFingerprint);

        return new Harness(controller, mappingProposalRepository, importBatchRepository, registry,
                proposalDecisionService, clientConfigFingerprint, proposalService);
    }

    private StoredMappingProposal storedProposal(String clientConfigFingerprint) {
        return new StoredMappingProposal(1L, 10L, 1, DUMMY_PROPOSAL, "PENDING", null,
                "MODEL", null, "col-fp", clientConfigFingerprint);
    }

    private ImportBatch batch() {
        return new ImportBatch(10L, "Holdings", "jpmc", "f.xlsx", "hash", "Holdings", 1, "PENDING");
    }

    @Test
    void approveRejectsWhenTheClientConfigFingerprintHasDrifted() {
        Harness h = harness();
        when(h.mappingProposalRepository().findById(1L)).thenReturn(storedProposal("old-fingerprint"));
        when(h.importBatchRepository().findById(10L)).thenReturn(batch());
        ClientConfig client = mock(ClientConfig.class);
        when(h.registry().getClient("jpmc")).thenReturn(client);
        // The config has since changed -- hash() now returns something
        // different from what the stored proposal was created against.
        when(h.clientConfigFingerprint().hash(client)).thenReturn("new-fingerprint");

        assertThatThrownBy(() -> h.controller().approve(1L, "reviewer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different client")
                .hasMessageContaining("jpmc");

        // The actual proof: the proposal was never even claimed, let
        // alone approved or dispatched -- the check runs before
        // anything else, so a drifted config can't be approved even
        // partially.
        verify(h.proposalDecisionService(), never()).claimForApproval(anyLong(), anyLong(), anyString());
    }

    @Test
    void approveProceedsWhenTheClientConfigFingerprintMatches() {
        Harness h = harness();
        when(h.mappingProposalRepository().findById(1L)).thenReturn(storedProposal("same-fingerprint"));
        when(h.importBatchRepository().findById(10L)).thenReturn(batch());
        ClientConfig client = mock(ClientConfig.class);
        when(h.registry().getClient("jpmc")).thenReturn(client);
        when(h.clientConfigFingerprint().hash(client)).thenReturn("same-fingerprint");
        // Short-circuits the test right after the fingerprint check
        // passes -- proving execution reached claimForApproval without
        // needing to mock the entire downstream validate-and-dispatch
        // pipeline just to let approve() return normally.
        org.mockito.Mockito.doThrow(new RuntimeException("stop-here-proof-of-reaching-claim"))
                .when(h.proposalDecisionService()).claimForApproval(1L, 10L, "reviewer");

        assertThatThrownBy(() -> h.controller().approve(1L, "reviewer"))
                .hasMessageContaining("stop-here-proof-of-reaching-claim");

        verify(h.proposalDecisionService()).claimForApproval(1L, 10L, "reviewer");
    }

    @Test
    void amendPersistsTheFreshFingerprintNotTheStaleStoredOne() throws Exception {
        Harness h = harness();
        when(h.mappingProposalRepository().findById(1L)).thenReturn(storedProposal("old-fingerprint"));
        when(h.importBatchRepository().findById(10L)).thenReturn(batch());
        CanonicalModel model = mock(CanonicalModel.class);
        when(h.registry().get("Holdings")).thenReturn(model);
        ClientConfig client = mock(ClientConfig.class);
        when(h.registry().getClient("jpmc")).thenReturn(client);
        // The config has changed since the original proposal -- this is
        // what a fresh /amend should now record, not "old-fingerprint".
        when(h.clientConfigFingerprint().hash(client)).thenReturn("fresh-fingerprint");
        when(h.proposalDecisionService().amendProposal(
                        anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), anyString(), anyString()))
                .thenReturn(2L);

        h.controller().amend(1L, DUMMY_PROPOSAL);

        // The actual proof: amendProposal was called with the FRESH
        // fingerprint just computed from the current client, never the
        // stale one this proposal's own (now-superseded) predecessor
        // was originally stored under.
        verify(h.proposalDecisionService()).amendProposal(
                eq(1L), eq(10L), org.mockito.ArgumentMatchers.anyInt(), eq(DUMMY_PROPOSAL),
                eq("col-fp"), eq("fresh-fingerprint"));
    }
}
