package com.alai.agenticsheets.mapping;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Postgres integration tests for {@link ProposalDecisionService}'s
 * transactional guarantees -- the property manual curl testing
 * repeatedly proved ambiguous to isolate (see {@code mapping-notes.md}'s
 * Step 7.5 section for the actual attempt and why it couldn't
 * deterministically force the exact window in question).
 *
 * {@code @Transactional} does nothing at all without a real Spring AOP
 * proxy wrapping the bean, so these tests build a small, hand-picked
 * {@code @Configuration} rather than reusing {@code @SpringBootTest} --
 * deliberately, given this project's repeated history with Boot
 * autoconfiguration surfacing problems unrelated to whatever was
 * actually being tested (the MCP client's eager initialization, the
 * OpenAI starter needing a placeholder key, and so on). A minimal
 * context with exactly the beans these tests need sidesteps all of
 * that, at the cost of needing {@code @EnableTransactionManagement}
 * explicitly -- a plain (non-Boot) Spring context doesn't auto-enable
 * that the way {@code @SpringBootTest} would.
 *
 * This is the first use of Testcontainers anywhere in this project.
 * Everything here follows well-established, standard patterns, but
 * unlike the rest of this codebase it hasn't been proven out repeatedly
 * in a real build the way the repository/controller code has -- flagged
 * honestly rather than presented with more confidence than is actually
 * warranted.
 */
@Testcontainers
class ProposalDecisionServiceTransactionalTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16")
        .withDatabaseName("agentic_sheets_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("orchestration-schema-test-init.sql");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(postgres.getJdbcUrl());
            ds.setUsername(postgres.getUsername());
            ds.setPassword(postgres.getPassword());
            ds.setDriverClassName(postgres.getDriverClassName());
            // At least a few real connections -- the concurrency test
            // below needs two threads to genuinely run at once, not
            // serialize waiting for a single pooled connection.
            ds.setMaximumPoolSize(5);
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(HikariDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
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
        ProposalDecisionService proposalDecisionService(
                MappingProposalRepository mappingProposalRepository, ImportBatchRepository importBatchRepository) {
            return new ProposalDecisionService(mappingProposalRepository, importBatchRepository);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static ImportBatchRepository importBatchRepository;
    private static MappingProposalRepository mappingProposalRepository;
    private static ProposalDecisionService proposalDecisionService;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpContext() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        importBatchRepository = context.getBean(ImportBatchRepository.class);
        mappingProposalRepository = context.getBean(MappingProposalRepository.class);
        proposalDecisionService = context.getBean(ProposalDecisionService.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
    }

    private static final MappingProposal DUMMY_PROPOSAL =
            new MappingProposal(List.of(), List.of(), "test proposal");

    private long newPendingBatch(String suffix) {
        return importBatchRepository.findOrCreate(
                "Holdings", "test-client", "test-file-" + suffix + ".xlsx", "hash-" + suffix, "Sheet1", 1);
    }

    private long newPendingProposal(long batchId) {
        return mappingProposalRepository.save(batchId, 1, DUMMY_PROPOSAL);
    }

    @Test
    void rollsBackTheProposalClaimWhenTheBatchClaimFails() {
        // A batch that is NOT PENDING -- claimForApproval's batch claim
        // (which only accepts PENDING) should fail against it.
        long batchId = newPendingBatch("rollback");
        jdbcTemplate.update("UPDATE import_batch SET status = 'DELIVERED' WHERE id = ?", batchId);
        long proposalId = newPendingProposal(batchId);

        assertThatThrownBy(() -> proposalDecisionService.claimForApproval(proposalId, batchId, "reviewer"))
                .isInstanceOf(IllegalStateException.class);

        // The real assertion: the proposal claim, which succeeded
        // *before* the batch claim failed, must have been rolled back
        // by the same transaction -- not left committed as APPROVED
        // with no batch to match.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM mapping_proposal WHERE id = ?", String.class, proposalId);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void exactlyOneOfTwoConcurrentApprovalsSucceeds() throws Exception {
        long batchId = newPendingBatch("concurrency");
        long proposalId = newPendingProposal(batchId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Runnable attempt = () -> {
            readyLatch.countDown();
            try {
                startLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                proposalDecisionService.claimForApproval(proposalId, batchId, "reviewer");
                successCount.incrementAndGet();
            } catch (IllegalStateException e) {
                failureCount.incrementAndGet();
            }
        };

        Future<?> f1 = executor.submit(attempt);
        Future<?> f2 = executor.submit(attempt);
        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).as("exactly one concurrent approval should succeed").isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        String proposalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM mapping_proposal WHERE id = ?", String.class, proposalId);
        String batchStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM import_batch WHERE id = ?", String.class, batchId);
        assertThat(proposalStatus).isEqualTo("APPROVED");
        assertThat(batchStatus).isEqualTo("PROCESSING");
    }
}
