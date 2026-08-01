package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.CanonicalModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Step 10's other half of the memory lifecycle -- {@link MappingResolutionService}
 * reads memory; this writes it. Two entry points:
 *
 * <ul>
 * <li>{@link #promoteIfEligible} -- called once a proposal has been
 * approved *and* validated with zero row errors (not merely
 * {@code status == APPROVED}; confirmed by reading {@code MappingController}
 * directly that a batch can reach {@code DELIVERED} with some rows
 * valid and others erroring -- partial success is not the same claim
 * as "this mapping was fully correct"). Delivery success is
 * deliberately not required -- a network failure says nothing about
 * whether the mapping itself was right.</li>
 * <li>{@link #invalidateIfMemoryDerived} -- called on rejection. A
 * memory-derived proposal that a human rejected means the remembered
 * mapping was wrong for this file after all (structural fingerprint
 * matches don't guarantee semantic correctness forever); disables the
 * entry so it stops producing the same rejected mapping on every
 * future structurally-similar file.</li>
 * </ul>
 */
@Service
public class MappingMemoryService {

    private static final Logger log = LoggerFactory.getLogger(MappingMemoryService.class);

    private final MappingMemoryRepository mappingMemoryRepository;

    public MappingMemoryService(MappingMemoryRepository mappingMemoryRepository) {
        this.mappingMemoryRepository = mappingMemoryRepository;
    }

    public void promoteIfEligible(StoredMappingProposal stored, ImportBatch batch, CanonicalModel model,
            ValidationReport validationReport) {
        if (validationReport.hasErrors()) {
            return;
        }
        if (stored.columnFingerprint() == null || stored.clientConfigFingerprint() == null) {
            // Only possible for a row predating this column existing --
            // nothing to key a memory entry on. Not an error, just
            // nothing to learn from here.
            return;
        }
        MappingMemoryEligibility.Result eligibility = MappingMemoryEligibility.check(stored.proposal());
        if (!eligibility.eligible()) {
            log.info("Proposal {} approved cleanly but not memorized -- {}", stored.id(), eligibility.reasons());
            return;
        }

        mappingMemoryRepository.promote(
                batch.clientId(), batch.worksheet(), model.modelId(), model.version(),
                stored.clientConfigFingerprint(), stored.columnFingerprint(), stored.proposal(), stored.id())
                .ifPresentOrElse(
                        memoryId -> log.info("Promoted proposal {} to mapping memory {}", stored.id(), memoryId),
                        () -> log.warn("Proposal {} approved cleanly but conflicted with an existing memory entry "
                                + "for the same scope -- that entry is now CONFLICTED, needs a human to look", stored.id()));
    }

    public void invalidateIfMemoryDerived(StoredMappingProposal stored, String reason) {
        if (ResolvedProposal.ORIGIN_MEMORY.equals(stored.origin()) && stored.mappingMemoryId() != null) {
            mappingMemoryRepository.invalidate(stored.mappingMemoryId(), reason);
            log.info("Invalidated mapping memory {} -- proposal {} it produced was rejected: {}",
                    stored.mappingMemoryId(), stored.id(), reason);
        }
    }
}
