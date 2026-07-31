package com.alai.agenticsheets.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Step 9: moves a delivered file's source out of the inbox, entirely
 * separate from proposing or approving -- never triggered by either.
 * That separation is deliberate, not incidental: {@code /approve}
 * re-hashes the source file at the path recorded when it was proposed
 * to detect drift ({@code SOURCE_CHANGED}), and that check can run well
 * after proposing, since a human has to review first. Moving the file
 * any earlier than {@code DELIVERED} -- including on {@code REJECTED},
 * which Step 7.4 deliberately made eligible for a fresh
 * {@code /propose} -- would break that check or a legitimate recovery
 * attempt with a "file not found" on an otherwise healthy batch.
 * {@code DELIVERED} is the only status in this whole system where
 * nothing will ever need to read the source file again.
 *
 * Gated by the same {@code agentic-sheets.inbox.enabled} property as
 * {@link InboxScanner} -- archiving without a scanner producing new
 * {@code inbox_file} rows to archive doesn't make sense as an
 * independently-toggled feature.
 */
@Component
@ConditionalOnProperty(name = "agentic-sheets.inbox.enabled", havingValue = "true")
public class InboxArchiver {

    private static final Logger log = LoggerFactory.getLogger(InboxArchiver.class);
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path workspaceRoot;
    private final InboxFileRepository inboxFileRepository;

    public InboxArchiver(
            @Value("${agentic-sheets.workspace-root:/workspace}") String workspaceRoot,
            InboxFileRepository inboxFileRepository) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.inboxFileRepository = inboxFileRepository;
    }

    @Scheduled(fixedDelayString = "${agentic-sheets.inbox.archive-interval-ms:300000}")
    public void archiveDelivered() {
        List<InboxFile> candidates = inboxFileRepository.findDeliveredAwaitingArchive();
        for (InboxFile candidate : candidates) {
            try {
                archiveOne(candidate);
            } catch (RuntimeException e) {
                log.error("Unexpected error archiving inbox file {}", candidate.logicalFilename(), e);
            }
        }
    }

    private void archiveOne(InboxFile file) {
        // Atomic: only one caller can ever archive a given row. If this
        // loses the race (another instance, or an already-archived row
        // this list happened to be built from a moment before that
        // completed), there's nothing to do -- not an error.
        if (!inboxFileRepository.claimForArchiving(file.id())) {
            return;
        }

        try {
            String destinationRelative = buildDestinationPath(file);
            Path source = workspaceRoot.resolve(file.currentPath()).toRealPath();
            Path destination = workspaceRoot.resolve(destinationRelative);
            Files.createDirectories(destination.getParent());

            // No overwrite -- a destination collision here would mean
            // this exact (client, feedType, date, batchId, hash,
            // filename) combination was somehow archived twice, which
            // the batchId/hash-prefix naming is specifically meant to
            // make impossible; failing loudly is correct if it somehow
            // still happens rather than silently overwriting.
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);

            inboxFileRepository.markArchived(file.id(), destinationRelative);
            log.info("Archived inbox file {} -> {}", file.logicalFilename(), destinationRelative);
        } catch (IOException e) {
            // Left claimed at ARCHIVING, deliberately not reverted to
            // PROPOSAL_CREATED here -- a partially-completed move (or
            // one that failed after the file was already gone from its
            // original path) needs a human or a future reconciliation
            // pass to look at, not an automatic retry that could double
            // -move or silently paper over a real problem. Named gap:
            // this reconciliation pass ("source missing but expected
            // destination exists") doesn't exist yet.
            log.error("Failed to archive inbox file {} -- left at ARCHIVING for manual investigation",
                    file.logicalFilename(), e);
        }
    }

    /** {@code archive/delivered/{client}/{feedType}/{date}/{batchId}-{hashPrefix}-{originalFilename}}
      * -- every component chosen specifically to make a collision
      * impossible even across many clients/feeds/dates/re-deliveries of
      * a same-named file. */
    private String buildDestinationPath(InboxFile file) {
        String hashPrefix = file.contentHash().substring(0, Math.min(12, file.contentHash().length()));
        String datePart = file.sourceDate() == null ? "unknown-date" : DATE_PATH_FORMAT.format(file.sourceDate());
        String clientPart = file.clientId() == null ? "unknown-client" : file.clientId();
        String feedPart = file.feedType() == null ? "unknown-feed" : file.feedType();
        return "archive/delivered/" + clientPart + "/" + feedPart + "/" + datePart + "/"
                + file.importBatchId() + "-" + hashPrefix + "-" + file.logicalFilename();
    }
}
