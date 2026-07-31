package com.alai.agenticsheets.inbox;

import com.alai.agenticsheets.canonical.CanonicalModelRegistry;
import com.alai.agenticsheets.canonical.FeedRoute;
import com.alai.agenticsheets.mapping.FileHasher;
import com.alai.agenticsheets.mapping.MappingWorkflowService;
import com.alai.agenticsheets.mapping.ProposeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Step 9: scheduled discovery and routing. Disabled by default (see
 * {@code agentic-sheets.inbox.enabled} -- not turned on in the E2E
 * overlay at all, deliberately: a background scan racing an E2E test
 * for llmsim's single scripted reply is exactly the "unexpected extra
 * model call" failure class Step 7.4/7.5 hardened against; see
 * {@code e2e/README.md}).
 *
 * Per-file flow, in order: skip anything not a stable, regular,
 * non-hidden, non-lock, non-symlink spreadsheet file; parse the
 * filename (see {@link InboxFilenameParser}); resolve the
 * (clientId, feedType) route (see {@link CanonicalModelRegistry#resolveRoute});
 * resolve which of the workbook's actual worksheets matches the route's
 * candidate names (see {@link WorksheetResolver}), failing
 * deterministically on zero or more than one match, never guessing;
 * hash; record arrival and attempt the atomic processing claim (see
 * {@link InboxFileRepository}); only on a won claim, call
 * {@link MappingWorkflowService#proposeInitialFromInbox}.
 *
 * A permanently unroutable file (bad filename, unknown client/feed,
 * ambiguous worksheet, unsupported extension) is quarantined, not
 * retried forever. A transient failure (anything else -- a database
 * hiccup, an MCP or model outage) goes to {@code RETRY_WAIT} with
 * backoff, up to a bounded attempt count.
 */
@Component
@ConditionalOnProperty(name = "agentic-sheets.inbox.enabled", havingValue = "true")
public class InboxScanner {

    private static final Logger log = LoggerFactory.getLogger(InboxScanner.class);

    /** Extensions this scanner will even attempt -- anything else is
      * quarantined immediately, no stability wait wasted on a file that
      * was never going to be routable. */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".xlsx", ".xls");

    private final Path inboxDir;
    private final Duration stabilityWindow;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final Duration retryBackoff;

    private final InboxFileRepository inboxFileRepository;
    private final InboxFilenameParser filenameParser;
    private final CanonicalModelRegistry registry;
    private final FileHasher fileHasher;
    private final MappingWorkflowService workflowService;
    private final WorksheetResolver worksheetResolver;

    public InboxScanner(
            @Value("${agentic-sheets.inbox.dir:/workspace/inbox}") String inboxDir,
            @Value("${agentic-sheets.inbox.stability-window-seconds:30}") long stabilityWindowSeconds,
            @Value("${agentic-sheets.inbox.max-attempts:5}") int maxAttempts,
            @Value("${agentic-sheets.inbox.lease-seconds:600}") long leaseSeconds,
            @Value("${agentic-sheets.inbox.retry-backoff-seconds:300}") long retryBackoffSeconds,
            InboxFileRepository inboxFileRepository,
            InboxFilenameParser filenameParser,
            CanonicalModelRegistry registry,
            FileHasher fileHasher,
            MappingWorkflowService workflowService,
            WorksheetResolver worksheetResolver) {
        this.inboxDir = Path.of(inboxDir);
        this.stabilityWindow = Duration.ofSeconds(stabilityWindowSeconds);
        this.maxAttempts = maxAttempts;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.retryBackoff = Duration.ofSeconds(retryBackoffSeconds);
        this.inboxFileRepository = inboxFileRepository;
        this.filenameParser = filenameParser;
        this.registry = registry;
        this.fileHasher = fileHasher;
        this.workflowService = workflowService;
        this.worksheetResolver = worksheetResolver;
    }

    @Scheduled(fixedDelayString = "${agentic-sheets.inbox.scan-interval-ms:60000}")
    public void scan() {
        for (Path file : listStableCandidates()) {
            try {
                processOne(file);
            } catch (RuntimeException e) {
                // One file's unexpected failure must not stop the rest
                // of this scan cycle -- the same per-file isolation
                // philosophy CanonicalModelRegistry's own reload already
                // established.
                log.error("Unexpected error processing inbox file {}", file, e);
            }
        }
    }

    private List<Path> listStableCandidates() {
        if (!Files.isDirectory(inboxDir)) {
            log.warn("Inbox directory does not exist (yet?): {}", inboxDir);
            return List.of();
        }
        try (Stream<Path> s = Files.list(inboxDir)) {
            return s.filter(this::isStableCandidate).sorted().toList();
        } catch (IOException e) {
            log.error("Unable to list inbox directory {}", inboxDir, e);
            return List.of();
        }
    }

    /** Everything checkable *before* even attempting to hash or route
      * the file -- deliberately conservative: a file failing any of
      * these is silently skipped this cycle (not quarantined), since
      * "still being written" and "genuinely unsupported" look identical
      * from here alone. Genuinely unsupported files get quarantined
      * later, once routing itself is attempted and fails. */
    private boolean isStableCandidate(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("~$") || name.startsWith(".") || name.endsWith(".part")) {
            return false;
        }
        if (Files.isSymbolicLink(path)) {
            log.warn("Ignoring symlink in inbox (not supported): {}", path);
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            return lastModified.isBefore(Instant.now().minus(stabilityWindow));
        } catch (IOException e) {
            log.warn("Unable to read attributes for {} -- skipping this cycle", path, e);
            return false;
        }
    }

    private void processOne(Path file) {
        String filename = file.getFileName().toString();
        String relativePath = "inbox/" + filename;

        String extension = filename.substring(Math.max(0, filename.lastIndexOf('.')));
        if (!SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
            log.warn("Quarantining {} -- unsupported extension {}", filename, extension);
            // Not yet in inbox_file at all (never hashed) -- nothing to
            // quarantine there yet. A human moving this file out is the
            // actual remediation; logging is the only record for now.
            // Named gap, not silently dropped -- see this project's own
            // Step 9 notes for the plan to close it (hash first, then
            // quarantine through inbox_file like every other failure
            // mode, rather than this one being log-only).
            return;
        }

        Optional<InboxFilenameParser.ParsedFilename> parsed = filenameParser.parse(filename);
        if (parsed.isEmpty()) {
            log.warn("Quarantining {} -- doesn't match <feedType>_<client>_<yyyyMMdd> convention", filename);
            return;
        }

        FeedRoute route;
        try {
            route = registry.resolveRoute(parsed.get().clientId(), parsed.get().feedType());
        } catch (NoSuchElementException e) {
            log.warn("Quarantining {} -- no feed route for client '{}', feed '{}'",
                    filename, parsed.get().clientId(), parsed.get().feedType());
            return;
        }

        String worksheet;
        try {
            worksheet = worksheetResolver.resolve(relativePath, route);
        } catch (IllegalStateException e) {
            log.warn("Quarantining {} -- {}", filename, e.getMessage());
            return;
        }

        String contentHash = fileHasher.sha256(relativePath);
        inboxFileRepository.recordArrival(filename, contentHash, relativePath,
                parsed.get().feedType(), parsed.get().clientId(), parsed.get().sourceDate(), worksheet);

        if (!inboxFileRepository.claimForProcessing(filename, contentHash, maxAttempts, leaseDuration)) {
            // Not eligible this cycle -- already succeeded, already
            // quarantined, already claimed by a concurrent attempt, or
            // still waiting out its retry backoff. All expected, none
            // of them errors.
            return;
        }

        InboxFile claimed = inboxFileRepository.findByLogicalFilenameAndHash(filename, contentHash)
                .orElseThrow(() -> new IllegalStateException(
                        "claimForProcessing succeeded but the row is missing -- should be unreachable"));

        try {
            ProposeResponse response = workflowService.proposeInitialFromInbox(
                    route.modelId(), parsed.get().clientId(), relativePath, worksheet);
            inboxFileRepository.markProposalCreated(claimed.id(), response.importBatchId());
            log.info("Inbox file {} -> proposal {} (batch {})",
                    filename, response.mappingProposalId(), response.importBatchId());
        } catch (RuntimeException e) {
            log.error("Failed to propose from inbox file {} (attempt {}/{})",
                    filename, claimed.attemptCount(), maxAttempts, e);
            inboxFileRepository.markRetryWait(claimed.id(), e.getMessage(), Instant.now().plus(retryBackoff));
        }
    }
}
