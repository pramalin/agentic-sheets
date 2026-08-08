package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import com.alai.agenticsheets.canonical.CanonicalModelParser;
import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConventionSuggestionService} -- Local LLM phase,
 * Step LLM-5 (see {@code docs/local-llm-enhancements.md}). Mocks the
 * repositories (the SQL-level dedup behavior itself is covered by
 * {@link ConventionSuggestionRepositoryTest} against a real Postgres);
 * this test is about the validation logic against a real canonical
 * model -- the same {@code holdings.yaml} fixture used throughout this
 * phase's tests.
 */
class ConventionSuggestionServiceTest {

    private final CanonicalModelParser parser = new CanonicalModelParser();

    private CanonicalModel holdings() throws Exception {
        Path file = Path.of(getClass().getClassLoader()
                .getResource("canonical-models/holdings.yaml").toURI());
        return parser.parse(file);
    }

    private ConventionSuggestionService serviceFor(CanonicalModel model,
            MappingProposalRepository proposalRepo, ImportBatchRepository batchRepo,
            ConventionSuggestionRepository suggestionRepo) {
        CanonicalModelRegistry registry = mock(CanonicalModelRegistry.class);
        when(registry.get("Holdings")).thenReturn(model);
        return new ConventionSuggestionService(proposalRepo, batchRepo, registry, suggestionRepo);
    }

    private StoredMappingProposal storedProposal(long batchId) {
        return new StoredMappingProposal(1L, batchId, 1,
                new MappingProposal(List.of(), List.of(), "test"), "APPROVED", null, "AGENT", null, "col", "cfg");
    }

    private ImportBatch batch(long id) {
        return new ImportBatch(id, "Holdings", "jpmc", "f.xlsx", "hash", "Holdings", 1, "APPROVED");
    }

    @Test
    void validFieldAliasSuggestion_isPersisted() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestion expected = new ConventionSuggestion(5L, 1L, "jpmc", "Holdings",
                ConventionSuggestion.KIND_FIELD_ALIAS, "currency", "Ccy", null,
                ConventionSuggestion.STATUS_PENDING, "reviewer", null, null);
        when(suggestionRepo.suggest(1L, "jpmc", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", null, "reviewer")).thenReturn(expected);

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        ConventionSuggestion result = service.suggest(1L, ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", null, "reviewer");

        assertThat(result).isEqualTo(expected);
        verify(suggestionRepo).suggest(1L, "jpmc", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", null, "reviewer");
    }

    @Test
    void validVariantValueSuggestion_isPersisted() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));
        when(suggestionRepo.suggest(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(ConventionSuggestion.class));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        service.suggest(1L, ConventionSuggestion.KIND_VARIANT_VALUE,
                "asset_class", "Fixed Income", "FixedIncome", "reviewer");

        verify(suggestionRepo).suggest(1L, "jpmc", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "asset_class", "Fixed Income", "FixedIncome", "reviewer");
    }

    @Test
    void fieldAliasReferencingUnknownFieldPath_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_FIELD_ALIAS,
                "nonexistent_field", "Alias", null, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent_field");
    }

    @Test
    void variantValueOnNonSumTypeField_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_VARIANT_VALUE,
                "account_id", "Some Value", "SomeVariant", "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a sum type field");
    }

    @Test
    void variantValueMappingToInvalidVariant_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "Bitcoin", "BTC", "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTC");
    }

    @Test
    void variantValueMissingTargetVariant_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "USD", null, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetVariant");
    }

    @Test
    void fieldAliasWithTargetVariantSet_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", "USD", "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetVariant");
    }

    @Test
    void unknownKind_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, "NOT_A_REAL_KIND",
                "currency", "USD", null, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown suggestion kind");
    }

    @Test
    void blankSourceValue_rejected() throws Exception {
        MappingProposalRepository proposalRepo = mock(MappingProposalRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        ConventionSuggestionRepository suggestionRepo = mock(ConventionSuggestionRepository.class);
        when(proposalRepo.findById(1L)).thenReturn(storedProposal(10L));
        when(batchRepo.findById(10L)).thenReturn(batch(10L));

        ConventionSuggestionService service = serviceFor(holdings(), proposalRepo, batchRepo, suggestionRepo);

        assertThatThrownBy(() -> service.suggest(1L, ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "  ", null, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceValue");
    }
}
