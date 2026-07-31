package com.alai.agenticsheets.inbox;

import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.FeedRoute;
import com.alai.agenticsheets.mapping.FileHasher;
import com.alai.agenticsheets.mapping.MappingWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirms the real point of this round's fix: a permanently-unroutable
 * file now produces an actual, queryable {@code QUARANTINED} row via
 * {@link InboxFileRepository#markQuarantined}, not just a log line that
 * would otherwise repeat every scan cycle forever. Each test stops the
 * file at a different one of the four quarantine-worthy checks
 * (extension, filename, route, worksheet) and confirms the pipeline
 * goes no further -- {@code proposeInitialFromInbox} is never called.
 */
class InboxScannerQuarantineTest {

    private InboxFileRepository inboxFileRepository;
    private InboxFilenameParser filenameParser;
    private CanonicalModelRegistry registry;
    private FileHasher fileHasher;
    private MappingWorkflowService workflowService;
    private WorksheetResolver worksheetResolver;

    @TempDir
    private Path inboxDir;

    @BeforeEach
    void setUp() {
        inboxFileRepository = mock(InboxFileRepository.class);
        filenameParser = mock(InboxFilenameParser.class);
        registry = mock(CanonicalModelRegistry.class);
        fileHasher = mock(FileHasher.class);
        workflowService = mock(MappingWorkflowService.class);
        worksheetResolver = mock(WorksheetResolver.class);

        when(fileHasher.sha256(anyString())).thenReturn("deadbeef");
        when(inboxFileRepository.claimForProcessing(anyString(), anyString(), anyInt(), any())).thenReturn(true);
    }

    // 0-second stability window -- so a file created moments ago in
    // this test still counts as stable, no real waiting needed.
    private InboxScanner scanner() {
        return new InboxScanner(
                inboxDir.toString(), 0L, 5, 600L, 300L,
                inboxFileRepository, filenameParser, registry, fileHasher, workflowService, worksheetResolver);
    }

    private Path createStableFile(String name) throws Exception {
        Path file = inboxDir.resolve(name);
        Files.writeString(file, "content");
        return file;
    }

    private InboxFile claimedRow(long id) {
        return new InboxFile(id, "whatever", "deadbeef", "inbox/whatever", "inbox/whatever",
                null, null, null, null, "PROCESSING", null, 1, null);
    }

    @Test
    void quarantinesAnUnsupportedExtension() throws Exception {
        createStableFile("notes_jpmc_20260115.docx");
        when(inboxFileRepository.findByLogicalFilenameAndHash(anyString(), anyString()))
                .thenReturn(Optional.of(claimedRow(1)));

        scanner().scan();

        verify(inboxFileRepository).markQuarantined(eq(1L), contains("unsupported extension"));
        verify(workflowService, never()).proposeInitialFromInbox(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void quarantinesAnUnparseableFilename() throws Exception {
        createStableFile("not_a_valid_name.xlsx");
        when(filenameParser.parse(anyString())).thenReturn(Optional.empty());
        when(inboxFileRepository.findByLogicalFilenameAndHash(anyString(), anyString()))
                .thenReturn(Optional.of(claimedRow(2)));

        scanner().scan();

        verify(inboxFileRepository).markQuarantined(eq(2L), anyString());
        verify(workflowService, never()).proposeInitialFromInbox(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void quarantinesAnUnknownRoute() throws Exception {
        createStableFile("holdings_unknownclient_20260115.xlsx");
        when(filenameParser.parse(anyString())).thenReturn(
                Optional.of(new InboxFilenameParser.ParsedFilename(
                        "holdings", "unknownclient", LocalDate.of(2026, 1, 15))));
        when(registry.resolveRoute("unknownclient", "holdings"))
                .thenThrow(new NoSuchElementException("no route"));
        when(inboxFileRepository.findByLogicalFilenameAndHash(anyString(), anyString()))
                .thenReturn(Optional.of(claimedRow(3)));

        scanner().scan();

        verify(inboxFileRepository).markQuarantined(eq(3L), anyString());
        verify(workflowService, never()).proposeInitialFromInbox(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void quarantinesAnAmbiguousWorksheet() throws Exception {
        createStableFile("holdings_jpmc_20260115.xlsx");
        when(filenameParser.parse(anyString())).thenReturn(
                Optional.of(new InboxFilenameParser.ParsedFilename(
                        "holdings", "jpmc", LocalDate.of(2026, 1, 15))));
        FeedRoute route = new FeedRoute("holdings", "Holdings", List.of("Holdings"));
        when(registry.resolveRoute("jpmc", "holdings")).thenReturn(route);
        when(worksheetResolver.resolve(anyString(), eq(route)))
                .thenThrow(new IllegalStateException("ambiguous: multiple worksheets matched"));
        when(inboxFileRepository.findByLogicalFilenameAndHash(anyString(), anyString()))
                .thenReturn(Optional.of(claimedRow(4)));

        scanner().scan();

        verify(inboxFileRepository).markQuarantined(eq(4L), contains("ambiguous"));
        verify(workflowService, never()).proposeInitialFromInbox(anyString(), anyString(), anyString(), anyString());
    }
}
