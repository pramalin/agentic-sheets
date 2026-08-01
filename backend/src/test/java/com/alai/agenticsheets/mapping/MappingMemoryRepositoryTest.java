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

/**
 * Real Postgres integration tests for {@link MappingMemoryRepository} --
 * same reasoning as {@link ProposalDecisionServiceTransactionalTest} for
 * why this needs a real database rather than mocks: the conflict-aware
 * {@code promote} logic (compare against an existing ACTIVE entry,
 * update-in-place vs. mark CONFLICTED) is exactly the kind of thing a
 * mock could assert was *called* without ever proving the actual SQL
 * does what it's supposed to. Same minimal hand-picked
 * {@code @Configuration} approach as that test, for the same reasons
 * (avoiding {@code @SpringBootTest} autoconfiguration surfacing
 * unrelated problems) -- no transaction manager needed here, since
 * nothing in {@link MappingMemoryRepository} is {@code @Transactional}.
 */
@Testcontainers
class MappingMemoryRepositoryTest {

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
        MappingMemoryRepository mappingMemoryRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
            return new MappingMemoryRepository(jdbcTemplate, jsonMapper);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static ImportBatchRepository importBatchRepository;
    private static MappingProposalRepository mappingProposalRepository;
    private static MappingMemoryRepository mappingMemoryRepository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpContext() throws java.io.IOException {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        importBatchRepository = context.getBean(ImportBatchRepository.class);
        mappingProposalRepository = context.getBean(MappingProposalRepository.class);
        mappingMemoryRepository = context.getBean(MappingMemoryRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);

        // Same single-source-of-truth reasoning as
        // ProposalDecisionServiceTransactionalTest -- the real schema
        // file, not a classpath copy that could drift from it.
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

    /** mapping_memory.source_proposal_id is a real foreign key -- every
      * test needs a genuine batch+proposal to reference, not an
      * arbitrary long. */
    private long newSourceProposal(String suffix) {
        long batchId = importBatchRepository.findOrCreate(
                "Holdings", "jpmc", "file-" + suffix + ".xlsx", "hash-" + suffix, "Holdings", 1);
        return mappingProposalRepository.save(batchId, 1, new MappingProposal(List.of(), List.of(), "test"));
    }

    private MappingProposal proposalWithSummary(String summary) {
        return new MappingProposal(List.of(), List.of(), summary);
    }

    @Test
    void findsNothingWhenNoEntryExistsForTheScope() {
        Optional<MappingMemory> found = mappingMemoryRepository.findActiveMatch(
                "no-such-client", "Holdings", "Holdings", 1, "cfg-x", "col-x");
        assertThat(found).isEmpty();
    }

    @Test
    void promotingANewScopeKeyCreatesAnActiveEntryThatCanThenBeFound() {
        long sourceProposalId = newSourceProposal("new-scope");
        MappingProposal proposal = proposalWithSummary("first approval");

        Optional<Long> memoryId = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-a", "col-a", proposal, sourceProposalId);

        assertThat(memoryId).isPresent();
        Optional<MappingMemory> found = mappingMemoryRepository.findActiveMatch(
                "jpmc", "Holdings", "Holdings", 1, "cfg-a", "col-a");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(memoryId.get());
        assertThat(found.get().status()).isEqualTo("ACTIVE");
        assertThat(found.get().proposal().summary()).isEqualTo("first approval");
        assertThat(found.get().sourceProposalId()).isEqualTo(sourceProposalId);
    }

    @Test
    void promotingAnIdenticalProposalForTheSameScopeIsANoOp() {
        long firstProposalId = newSourceProposal("idempotent-1");
        long secondProposalId = newSourceProposal("idempotent-2");
        MappingProposal proposal = proposalWithSummary("identical content");

        Optional<Long> firstMemoryId = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-b", "col-b", proposal, firstProposalId);
        // A second, later approval that happens to produce byte-for-byte
        // the same mapping -- should confirm the existing entry, not
        // create a second row or flag a conflict.
        Optional<Long> secondMemoryId = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-b", "col-b", proposal, secondProposalId);

        assertThat(secondMemoryId).isEqualTo(firstMemoryId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mapping_memory WHERE client_id = 'jpmc' AND column_fingerprint = 'col-b'",
                Integer.class);
        assertThat(rowCount).isEqualTo(1);
        assertThat(mappingMemoryRepository.findById(firstMemoryId.get()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void promotingADifferentProposalForTheSameScopeConflictsRatherThanOverwriting() {
        long firstProposalId = newSourceProposal("conflict-1");
        long secondProposalId = newSourceProposal("conflict-2");
        MappingProposal originalProposal = proposalWithSummary("original mapping");
        MappingProposal differentProposal = proposalWithSummary("a genuinely different mapping");

        Optional<Long> firstMemoryId = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-c", "col-c", originalProposal, firstProposalId);
        Optional<Long> secondResult = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-c", "col-c", differentProposal, secondProposalId);

        // The real assertion: no ACTIVE entry reported for the second
        // call (never silently "last write wins"), and the *original*
        // entry is now CONFLICTED, not overwritten with the new content.
        assertThat(secondResult).isEmpty();
        MappingMemory firstEntryAfterConflict = mappingMemoryRepository.findById(firstMemoryId.get());
        assertThat(firstEntryAfterConflict.status()).isEqualTo("CONFLICTED");
        assertThat(firstEntryAfterConflict.proposal().summary()).isEqualTo("original mapping");
        assertThat(firstEntryAfterConflict.invalidationReason()).contains(String.valueOf(secondProposalId));

        Optional<MappingMemory> stillFound = mappingMemoryRepository.findActiveMatch(
                "jpmc", "Holdings", "Holdings", 1, "cfg-c", "col-c");
        assertThat(stillFound).isEmpty();
    }

    @Test
    void invalidatingAnEntryRemovesItFromFutureMatchesButKeepsTheRow() {
        long sourceProposalId = newSourceProposal("invalidate");
        MappingProposal proposal = proposalWithSummary("later rejected");
        long memoryId = mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-d", "col-d", proposal, sourceProposalId).orElseThrow();

        mappingMemoryRepository.invalidate(memoryId, "rejected by reviewer");

        Optional<MappingMemory> found = mappingMemoryRepository.findActiveMatch(
                "jpmc", "Holdings", "Holdings", 1, "cfg-d", "col-d");
        assertThat(found).isEmpty();
        MappingMemory row = mappingMemoryRepository.findById(memoryId);
        assertThat(row.status()).isEqualTo("INVALIDATED");
        assertThat(row.invalidationReason()).isEqualTo("rejected by reviewer");
    }

    @Test
    void differentModelVersionsAreDistinctScopesNotACollision() {
        long v1ProposalId = newSourceProposal("versioned-1");
        long v2ProposalId = newSourceProposal("versioned-2");
        MappingProposal v1Proposal = proposalWithSummary("for version 1");
        MappingProposal v2Proposal = proposalWithSummary("for version 2");

        mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 1, "cfg-e", "col-e", v1Proposal, v1ProposalId);
        mappingMemoryRepository.promote(
                "jpmc", "Holdings", "Holdings", 2, "cfg-e", "col-e", v2Proposal, v2ProposalId);

        // Both should be independently ACTIVE -- a model version bump
        // is a genuinely different scope, not a conflict with the old
        // version's own remembered mapping.
        assertThat(mappingMemoryRepository.findActiveMatch(
                "jpmc", "Holdings", "Holdings", 1, "cfg-e", "col-e").map(m -> m.proposal().summary()))
                .contains("for version 1");
        assertThat(mappingMemoryRepository.findActiveMatch(
                "jpmc", "Holdings", "Holdings", 2, "cfg-e", "col-e").map(m -> m.proposal().summary()))
                .contains("for version 2");
    }
}
