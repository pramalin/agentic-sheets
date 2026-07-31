package com.alai.agenticsheets.mapping;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The read/reset side of {@link FakeTargetJournal} -- separated into
 * its own controller, conditional on the exact same property as the
 * journal bean itself, so this class (and every endpoint on it) simply
 * doesn't exist in a normal deployment's Spring context. A request to
 * {@code GET /internal/fake-target/_journal/{service}/requests} in
 * that case gets a plain 404 -- no matching handler at all, not a 401
 * from an auth check that could itself have a bug. See
 * {@code agentic-sheets.fake-target.journal-enabled} in
 * {@code compose.e2e.yaml}, the only place this is turned on.
 *
 * {@code /_journal} is a real fix for a real routing bug an external
 * review caught, not decoration: this used to live directly under
 * {@code /internal/fake-target}, meaning {@code POST
 * /internal/fake-target/reset} -- meant to hit this controller's own
 * {@code reset()} -- was only reachable when this controller's bean
 * actually existed. When the property is unset, {@link
 * FakeTargetController}'s {@code @PostMapping("/{service}")} is the
 * *only* handler left registered for that shape of URL, and Spring
 * happily matches it with {@code service = "reset"} instead of
 * returning 404 -- the request gets silently treated as an ordinary
 * fake-target delivery. A two-segment namespace under {@code
 * /_journal} can never collide with the single-segment {@code
 * /{service}} pattern, regardless of which beans happen to be
 * registered.
 */
@RestController
@RequestMapping("/internal/fake-target/_journal")
@ConditionalOnProperty(name = "agentic-sheets.fake-target.journal-enabled", havingValue = "true")
public class FakeTargetJournalController {

    private final FakeTargetJournal journal;

    public FakeTargetJournalController(FakeTargetJournal journal) {
        this.journal = journal;
    }

    /**
     * Every request {@code service} has received since the last reset,
     * oldest first -- the read side of the journal a test asserts
     * against, mirroring {@code GET /_llmsim/calls}'s exact shape for
     * the model-call side of the same pipeline (a full list, so both
     * "what was received" and "exactly how many times" are directly
     * assertable, not just the most recent one).
     */
    @GetMapping("/{service}/requests")
    public List<FakeTargetJournal.ReceivedRequest> requests(@PathVariable String service) {
        return journal.findAll(service);
    }

    /**
     * Clears the journal for every service -- lets one running instance
     * serve multiple test cases without restarting the container,
     * matching {@code POST /_llmsim/reset}'s same role for the model
     * side.
     */
    @PostMapping("/reset")
    public void reset() {
        journal.reset();
    }
}
