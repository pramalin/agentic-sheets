package com.alai.agenticsheets.mapping;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Postgres integration tests for {@link ConventionSuggestionRepository}
 * -- Local LLM phase, Step LLM-5 (see {@code docs/local-llm-enhancements.md}).
 * Same reasoning as {@link MappingMemoryRepositoryTest} for why this needs
 * a real database rather than mocks: the {@code ON CONFLICT ... DO NOTHING
 * RETURNING id} dedup logic against a partial unique index is exactly the
 * kind of thing a mock could assert was *called* without ever proving the
 * actual SQL -- and the partial-index predicate in particular (only over
 * {@code status = 'PENDING'} rows) is easy to get subtly wrong in a way
 * only a real constraint violation (or its absence) would catch.
 */
@Testcontainers
class ConventionSuggestionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("agentic_sheets_test")
            .withUsername("test")
            .withPassword("test");

    @Configuration
    static class TestConfig {

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(postgres.getJdbcUrl());
            ds.setUsername(postgres.getUsername());
            ds.setPassword(postgres.getPassword());
            ds.setDriverClassName(postgres.getDriverClassName());
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        ImportBatchRepository importBatchRepository(JdbcTemplate jdbcTemplate) {
            return new ImportBatchRepository(jdbcTemplate);
        }

        @Bean
        MappingProposalRepository mappingProposalRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
            return new MappingProposalRepository(jdbcTemplate, jsonMapper);
        }

        @Bean
        ConventionSuggestionRepository conventionSuggestionRepository(JdbcTemplate jdbcTemplate) {
            return new ConventionSuggestionRepository(jdbcTemplate);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static ImportBatchRepository importBatchRepository;
    private static MappingProposalRepository mappingProposalRepository;
    private static ConventionSuggestionRepository suggestionRepository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpContext() throws java.io.IOException {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        importBatchRepository = context.getBean(ImportBatchRepository.class);
        mappingProposalRepository = context.getBean(MappingProposalRepository.class);
        suggestionRepository = context.getBean(ConventionSuggestionRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);

        // Same single-source-of-truth reasoning as MappingMemoryRepositoryTest
        // -- the real schema file, not a classpath copy that could drift.
        String schemaSql = java.nio.file.Files.readString(
                java.nio.file.Path.of("../db/init/01-orchestration-schema.sql"));
        jdbcTemplate.execute(schemaSql);
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
    }

    /** convention_suggestion.source_proposal_id is a real foreign key --
      * every test needs a genuine batch+proposal to reference. */
    private long newSourceProposal(String suffix) {
        long batchId = importBatchRepository.findOrCreate(
                "Holdings", "jpmc", "file-" + suffix + ".xlsx", "hash-" + suffix, "Holdings", 1);
        return mappingProposalRepository.save(batchId, 1, new MappingProposal(List.of(), List.of(), "test"));
    }

    @Test
    void suggestingANewFactCreatesAPendingRow() {
        long proposalId = newSourceProposal("new-fact");

        ConventionSuggestion suggestion = suggestionRepository.suggest(
                proposalId, "jpmc", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "asset_class", "Fixed Income", "FixedIncome", "reviewer-a");

        assertThat(suggestion.status()).isEqualTo(ConventionSuggestion.STATUS_PENDING);
        assertThat(suggestion.clientId()).isEqualTo("jpmc");
        assertThat(suggestion.sourceValue()).isEqualTo("Fixed Income");
        assertThat(suggestion.targetVariant()).isEqualTo("FixedIncome");
        assertThat(suggestion.suggestedBy()).isEqualTo("reviewer-a");
        assertThat(suggestion.createdAt()).isNotNull();
        assertThat(suggestion.resolvedAt()).isNull();
    }

    @Test
    void suggestingTheSameFactTwiceReturnsTheSameRowNotADuplicate() {
        long firstProposalId = newSourceProposal("dup-1");
        long secondProposalId = newSourceProposal("dup-2");

        ConventionSuggestion first = suggestionRepository.suggest(
                firstProposalId, "jpmc", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "asset_class", "Fixed Income", "FixedIncome", "reviewer-a");
        // A second reviewer, on a different file, independently notices
        // the exact same fact -- should confirm the existing suggestion,
        // not create a second PENDING row for it.
        ConventionSuggestion second = suggestionRepository.suggest(
                secondProposalId, "jpmc", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "asset_class", "Fixed Income", "FixedIncome", "reviewer-b");

        assertThat(second.id()).isEqualTo(first.id());
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM convention_suggestion WHERE client_id = 'jpmc' "
                        + "AND canonical_field_path = 'asset_class' AND source_value = 'Fixed Income'",
                Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void dismissingThenReSuggestingTheSameFactCreatesAFreshPendingRow() {
        long firstProposalId = newSourceProposal("dismiss-then-resuggest-1");
        long secondProposalId = newSourceProposal("dismiss-then-resuggest-2");

        ConventionSuggestion first = suggestionRepository.suggest(
                firstProposalId, "acme", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", null, "reviewer-a");
        suggestionRepository.dismiss(first.id());

        // The partial unique index only covers PENDING rows -- a
        // dismissed suggestion must not block a fresh one for the same
        // fact (a reviewer might reasonably reconsider later).
        ConventionSuggestion second = suggestionRepository.suggest(
                secondProposalId, "acme", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy", null, "reviewer-b");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(ConventionSuggestion.STATUS_PENDING);
        assertThat(suggestionRepository.findById(first.id()).status())
                .isEqualTo(ConventionSuggestion.STATUS_DISMISSED);
    }

    @Test
    void dismissingSetsStatusAndResolvedAt() {
        long proposalId = newSourceProposal("dismiss");
        ConventionSuggestion suggestion = suggestionRepository.suggest(
                proposalId, "jpmc", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Curr", null, "reviewer-a");

        suggestionRepository.dismiss(suggestion.id());

        ConventionSuggestion dismissed = suggestionRepository.findById(suggestion.id());
        assertThat(dismissed.status()).isEqualTo(ConventionSuggestion.STATUS_DISMISSED);
        assertThat(dismissed.resolvedAt()).isNotNull();
    }

    @Test
    void dismissingAnAlreadyResolvedSuggestionFails() {
        long proposalId = newSourceProposal("double-dismiss");
        ConventionSuggestion suggestion = suggestionRepository.suggest(
                proposalId, "jpmc", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "Ccy2", null, "reviewer-a");
        suggestionRepository.dismiss(suggestion.id());

        assertThatThrownBy(() -> suggestionRepository.dismiss(suggestion.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not PENDING");
    }

    @Test
    void findByClientAndStatusFiltersCorrectly() {
        long p1 = newSourceProposal("filter-1");
        long p2 = newSourceProposal("filter-2");
        long p3 = newSourceProposal("filter-3");

        ConventionSuggestion pending = suggestionRepository.suggest(
                p1, "filter-client", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "AliasA", null, "r");
        ConventionSuggestion toDismiss = suggestionRepository.suggest(
                p2, "filter-client", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "AliasB", null, "r");
        suggestionRepository.dismiss(toDismiss.id());
        // A different client's suggestion must never leak into this
        // client's queue.
        suggestionRepository.suggest(
                p3, "some-other-client", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS,
                "currency", "AliasA", null, "r");

        List<ConventionSuggestion> pendingList =
                suggestionRepository.findByClientAndStatus("filter-client", ConventionSuggestion.STATUS_PENDING);
        List<ConventionSuggestion> dismissedList =
                suggestionRepository.findByClientAndStatus("filter-client", ConventionSuggestion.STATUS_DISMISSED);

        assertThat(pendingList).extracting(ConventionSuggestion::id).containsExactly(pending.id());
        assertThat(dismissedList).extracting(ConventionSuggestion::id).containsExactly(toDismiss.id());
    }

    @Test
    void findPendingReturnsEmptyWhenNoneExists() {
        Optional<ConventionSuggestion> found = suggestionRepository.findPending(
                "no-such-client", "Holdings", ConventionSuggestion.KIND_FIELD_ALIAS, "currency", "X");
        assertThat(found).isEmpty();
    }

    // External review finding (post Step LLM-6): the unique index
    // doesn't include target_variant, so a genuinely conflicting
    // suggestion (same source value, different target) was silently
    // returning the FIRST row rather than surfacing the disagreement.
    // See docs/local-llm-enhancements.md.

    @Test
    void conflictingTargetForSameSourceValue_throwsRatherThanSilentlyReturningTheFirstRow() {
        long p1 = newSourceProposal("conflict-1");
        long p2 = newSourceProposal("conflict-2");

        // The review's own exact example.
        suggestionRepository.suggest(
                p1, "conflict-client", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "USD", "USD", "reviewer-a");

        assertThatThrownBy(() -> suggestionRepository.suggest(
                p2, "conflict-client", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "USD", "EUR", "reviewer-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR")
                .hasMessageContaining("conflicting");
    }

    @Test
    void sameTargetForSameSourceValue_stillIdempotentNotAConflict() {
        // The non-conflicting case must still behave exactly as before --
        // confirming the same fact is not a disagreement.
        long p1 = newSourceProposal("agree-1");
        long p2 = newSourceProposal("agree-2");

        ConventionSuggestion first = suggestionRepository.suggest(
                p1, "agree-client", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "USD", "USD", "reviewer-a");
        ConventionSuggestion second = suggestionRepository.suggest(
                p2, "agree-client", "Holdings", ConventionSuggestion.KIND_VARIANT_VALUE,
                "currency", "USD", "USD", "reviewer-b");

        assertThat(second.id()).isEqualTo(first.id());
    }
}
